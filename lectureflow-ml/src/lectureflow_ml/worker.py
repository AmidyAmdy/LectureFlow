import logging
import os
import queue
import shutil
import signal
import threading
import uuid
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass
from functools import partial
from pathlib import Path
from time import monotonic, perf_counter
from typing import Any

from pika.exceptions import (
    AMQPChannelError,
    AMQPConnectionError,
    AuthenticationError,
    ProbableAccessDeniedError,
    ProbableAuthenticationError,
)

from . import messages, rabbit, settings
from .job import JobError, JobServices, describe_error, process_job
from .messages import ATTEMPTS_HEADER
from .settings import WorkerSettings

logger = logging.getLogger(__name__)


POLL_SECONDS = 0.2
LAST_STAGE_HEADER = "x-lectureflow-last-stage"
LAST_ERROR_HEADER = "x-lectureflow-last-error"
_FATAL_CONNECTION_ERRORS = (
    AuthenticationError,
    ProbableAuthenticationError,
    ProbableAccessDeniedError,
    rabbit.QueueConfigError,
)


class StopRequested(Exception):
    pass


class ReconnectNeeded(Exception):
    pass


class ConnectionLostError(RuntimeError):
    pass


@dataclass(frozen=True)
class Delivery:
    generation: int
    delivery_tag: int
    redelivered: bool
    properties: Any
    body: bytes


class BackgroundRunner:
    def __init__(self) -> None:
        self._tasks: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._pending = 0
        self._thread: threading.Thread | None = None

    @property
    def busy(self) -> bool:
        with self._lock:
            return self._pending > 0

    def submit(self, function: Callable[[], Any]) -> Future:
        future: Future = Future()
        with self._lock:
            self._pending += 1
            if self._thread is None:
                self._thread = threading.Thread(
                    target=self._loop, name="lectureflow-job", daemon=True
                )
                self._thread.start()
        self._tasks.put((function, future))
        return future

    def _loop(self) -> None:
        while True:
            function, future = self._tasks.get()
            result, error = None, None
            if future.set_running_or_notify_cancel():
                try:
                    result = function()
                except BaseException as exc:
                    error = exc
            with self._lock:
                self._pending -= 1
            if error is not None:
                future.set_exception(error)
            elif not future.cancelled():
                future.set_result(result)


class PublishGate:
    def __init__(self) -> None:
        self._requests: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._closed = False

    def publish(self, queue_name: str, payload: dict) -> None:

        future: Future = Future()
        with self._lock:
            if self._closed:
                raise ConnectionLostError("Соединение задания с RabbitMQ закрыто")
            self._requests.put((queue_name, payload, future))
        future.result()

    def serve(self, handler: Callable[[str, dict], None]) -> None:

        while True:
            try:
                queue_name, payload, future = self._requests.get_nowait()
            except queue.Empty:
                return
            try:
                handler(queue_name, payload)
            except rabbit.PublishError as exc:
                future.set_exception(exc)
            except BaseException as exc:
                future.set_exception(
                    ConnectionLostError(f"Публикация прервана: {exc!r}")
                )
                raise
            else:
                future.set_result(None)

    def close(self) -> None:
        with self._lock:
            self._closed = True
            while True:
                try:
                    _, _, future = self._requests.get_nowait()
                except queue.Empty:
                    return
                future.set_exception(
                    ConnectionLostError("Соединение с RabbitMQ закрыто")
                )


class Worker:
    def __init__(
        self,
        worker_settings: WorkerSettings,
        services: JobServices,
        *,
        connect: Callable[[], Any] | None = None,
        process: Callable[..., Any] = process_job,
        poll_seconds: float = POLL_SECONDS,
    ) -> None:
        self.settings = worker_settings
        self._services = services
        self._connect = connect or partial(rabbit.connect, worker_settings)
        self._process = process
        self._poll_seconds = poll_seconds
        self._runner = BackgroundRunner()
        self._stop = threading.Event()
        self._connection: Any = None
        self._channel: Any = None
        self._generation = 0

    @property
    def job_in_progress(self) -> bool:
        return self._runner.busy

    def request_stop(self, reason: str = "запрошена остановка") -> None:
        if not self._stop.is_set():
            logger.warning(
                "Остановка (%s): новые задания не принимаются. "
                "Незавершённое задание не будет подтверждено и останется в очереди. "
                "Повторный Ctrl+C — немедленный выход",
                reason,
            )
        self._stop.set()

    def install_signal_handlers(self) -> Callable[[], None]:
        signals = (signal.SIGINT, signal.SIGTERM)
        previous = {sig: signal.signal(sig, self._on_signal) for sig in signals}

        def restore() -> None:
            for sig, handler in previous.items():
                signal.signal(sig, handler)

        return restore

    def _on_signal(self, signum: int, frame: object) -> None:
        if self._stop.is_set():
            raise KeyboardInterrupt
        self.request_stop(f"сигнал {signal.Signals(signum).name}")

    def run(self) -> int:

        try:
            while not self._stop.is_set():
                try:
                    self._serve_connection()
                except StopRequested:
                    break
                except _FATAL_CONNECTION_ERRORS as exc:
                    logger.error(
                        "RabbitMQ: %s. Проверьте настройки; worker остановлен", exc
                    )
                    return 1
                except (AMQPConnectionError, AMQPChannelError, ReconnectNeeded) as exc:
                    if self._stop.is_set():
                        break
                    delay = self.settings.reconnect_delay_seconds
                    logger.warning(
                        "Нет соединения с RabbitMQ (%s); новая попытка через %.0f с",
                        describe_error(exc),
                        delay,
                    )
                    self._close()
                    self._stop.wait(delay)
            return 0
        finally:
            self._close()

    def _serve_connection(self) -> None:
        self._generation += 1
        self._connection = self._connect()
        self._channel = rabbit.open_channel(self._connection, self.settings)
        logger.info(
            "Подключено к RabbitMQ, жду задания в очереди %s",
            self.settings.transcription_queue,
        )
        consumer = self._channel.consume(
            self.settings.transcription_queue, inactivity_timeout=self._poll_seconds
        )
        for method, properties, body in consumer:
            if self._stop.is_set():
                raise StopRequested
            if method is None:
                continue
            self._handle(
                Delivery(
                    generation=self._generation,
                    delivery_tag=method.delivery_tag,
                    redelivered=bool(method.redelivered),
                    properties=properties,
                    body=body or b"",
                )
            )
        raise ReconnectNeeded("брокер отменил подписку на очередь")

    def _handle(self, delivery: Delivery) -> None:
        attempt = previous_attempts(delivery.properties) + 1
        try:
            task = messages.parse_task(delivery.body)
        except messages.InvalidTaskError as exc:
            logger.error(
                "Задание deliveryTag=%s отклонено: %s", delivery.delivery_tag, exc
            )
            self._send_to_failed(
                delivery, "validate", str(exc), attempt, permanent=True
            )
            return

        context = task.log_context()
        logger.info(
            "%s: получено задание, попытка %d из %d%s",
            context,
            attempt,
            self.settings.max_attempts,
            " (повторная доставка)" if delivery.redelivered else "",
        )
        started = perf_counter()
        error = self._run_job(task)
        elapsed = perf_counter() - started
        if error is None:
            self._ack(delivery)
            logger.info(
                "%s: задание выполнено за %.1f с и подтверждено", context, elapsed
            )
        elif error.permanent or attempt >= self.settings.max_attempts:
            self._send_to_failed(
                delivery, error.stage, str(error), attempt, permanent=error.permanent
            )
        else:
            self._retry(delivery, context, attempt, error)

    def _run_job(self, task: messages.TranscriptionTask) -> JobError | None:
        gate = PublishGate()
        future = self._runner.submit(
            partial(self._process, task, self._services, gate.publish)
        )
        try:
            while not future.done():
                if self._stop.is_set():
                    raise StopRequested
                self._connection.process_data_events(time_limit=self._poll_seconds)
                gate.serve(self._publish_for_job)
        except BaseException:
            gate.close()
            logger.warning(
                "%s: задание прервано и не подтверждено; сообщение будет доставлено повторно",
                task.log_context(),
            )
            raise
        gate.close()
        exc = future.exception()
        if exc is None or isinstance(exc, JobError):
            return exc
        return JobError("unknown", describe_error(exc), permanent=False)

    def _publish_for_job(self, queue_name: str, payload: dict) -> None:
        rabbit.publish_json(self._channel, queue_name, payload)

    def _retry(
        self, delivery: Delivery, context: str, attempt: int, error: JobError
    ) -> None:
        headers = dict(getattr(delivery.properties, "headers", None) or {})
        headers[ATTEMPTS_HEADER] = attempt
        headers[LAST_STAGE_HEADER] = error.stage
        headers[LAST_ERROR_HEADER] = messages.truncate(str(error), 500)
        self._publish_or_reconnect(
            self.settings.transcription_queue,
            delivery.body,
            headers=headers,
            message_id=getattr(delivery.properties, "message_id", None),
        )
        self._ack(delivery)
        delay = self.settings.retry_delay_seconds * 2 ** (attempt - 1)
        logger.warning(
            "%s: временная ошибка на этапе %s (попытка %d из %d): %s. "
            "Задание возвращено в очередь, следующая попытка не раньше чем через %.0f с",
            context,
            error.stage,
            attempt,
            self.settings.max_attempts,
            error,
            delay,
        )
        self._pause(delay)

    def _send_to_failed(
        self,
        delivery: Delivery,
        stage: str,
        error: str,
        attempts: int,
        *,
        permanent: bool,
    ) -> None:
        payload = messages.failure_message(
            delivery.body,
            stage=stage,
            error=error,
            attempts=attempts,
            max_attempts=self.settings.max_attempts,
            permanent=permanent,
        )
        self._publish_or_reconnect(
            self.settings.failed_queue, messages.encode_json(payload)
        )
        self._ack(delivery)
        logger.error(
            "recordingId=%s audioObjectKey=%s: отправлено в очередь ошибок %s "
            "(этап=%s, попыток=%d, %s)",
            payload["recordingId"],
            payload["audioObjectKey"],
            self.settings.failed_queue,
            stage,
            attempts,
            "постоянная ошибка" if permanent else "попытки исчерпаны",
        )

    def _publish_or_reconnect(
        self, queue_name: str, body: bytes, **kwargs: Any
    ) -> None:
        try:
            rabbit.publish(self._channel, queue_name, body, **kwargs)
        except rabbit.PublishError as exc:
            raise ReconnectNeeded(
                f"{exc}; исходное задание не подтверждено и будет доставлено повторно"
            ) from exc

    def _ack(self, delivery: Delivery) -> None:
        if delivery.generation != self._generation or self._channel is None:
            raise RuntimeError("delivery_tag прежнего соединения нельзя подтверждать")
        self._channel.basic_ack(delivery_tag=delivery.delivery_tag)

    def _pause(self, seconds: float) -> None:

        deadline = monotonic() + seconds
        while not self._stop.is_set():
            remaining = deadline - monotonic()
            if remaining <= 0:
                return
            self._connection.process_data_events(
                time_limit=min(self._poll_seconds, remaining)
            )

    def _close(self) -> None:

        connection, self._connection, self._channel = self._connection, None, None
        if connection is None:
            return
        try:
            if connection.is_open:
                connection.close()
                logger.info("Соединение с RabbitMQ закрыто")
        except Exception as exc:
            logger.debug("Ошибка при закрытии соединения: %r", exc)


def previous_attempts(properties: Any) -> int:
    headers = getattr(properties, "headers", None) or {}
    value = headers.get(ATTEMPTS_HEADER, 0)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        logger.warning(
            "Некорректный заголовок %s=%r, считаю его равным 0", ATTEMPTS_HEADER, value
        )
        return 0
    return value


def build_services(worker_settings: WorkerSettings, work_dir: Path) -> JobServices:

    from ollama import Client

    from .storage import S3Storage
    from .summarization import summarize_segments
    from .transcription import transcribe_audio

    storage = S3Storage.from_settings(worker_settings)
    try:
        storage.check_bucket()
    except Exception as exc:
        raise RuntimeError(
            f"S3 {worker_settings.s3_endpoint}: бакет {worker_settings.s3_bucket} "
            f"недоступен ({describe_error(exc)})"
        ) from exc
    client = Client(host=settings.OLLAMA_HOST, timeout=settings.OLLAMA_TIMEOUT_SECONDS)
    try:
        client.show(settings.OLLAMA_MODEL)
    except Exception as exc:
        raise RuntimeError(
            f"Ollama {settings.OLLAMA_HOST}: модель {settings.OLLAMA_MODEL} недоступна "
            f"({describe_error(exc)}). Проверьте `ollama serve` и `ollama pull {settings.OLLAMA_MODEL}`"
        ) from exc
    return JobServices(
        storage=storage,
        transcribe=transcribe_audio,
        summarize=partial(summarize_segments, client=client),
        transcription_completed_queue=worker_settings.transcription_completed_queue,
        summary_completed_queue=worker_settings.summary_completed_queue,
        work_dir=work_dir,
    )


def run_worker() -> int:
    try:
        worker_settings = WorkerSettings.from_env()
    except ValueError as exc:
        logger.error("Ошибка настроек: %s", exc)
        return 1
    logger.info("Запуск worker. %s", worker_settings.describe())

    os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")

    work_dir = worker_settings.work_dir / f"worker-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    try:
        services = build_services(worker_settings, work_dir)
    except Exception as exc:
        logger.error("%s", exc)
        return 1
    worker = Worker(worker_settings, services)
    restore_signals = worker.install_signal_handlers()
    try:
        code = worker.run()
    except KeyboardInterrupt:
        logger.warning("Принудительная остановка")
        code = 130
    finally:
        restore_signals()
        shutil.rmtree(work_dir, ignore_errors=True)
    if worker.job_in_progress:
        logger.warning("Незавершённое задание прервано и будет обработано повторно")
        logging.shutdown()
        os._exit(code)
    logger.info("Worker остановлен")
    return code
