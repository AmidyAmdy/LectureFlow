import json
import signal
import threading
from unittest.mock import Mock

import pika
import pytest
from fakes import FakeBroker, FakeStorage
from pika.exceptions import NackError, UnroutableError

from lectureflow_ml.job import JobServices
from lectureflow_ml.messages import ATTEMPTS_HEADER
from lectureflow_ml.settings import WorkerSettings
from lectureflow_ml.worker import Delivery, Worker

AUDIO_KEY = "recordings/5/upload.m4a"
TASK_BODY = json.dumps({"recordingId": 5, "audioObjectKey": AUDIO_KEY}).encode()
SEGMENTS = [{"start": 0.0, "end": 2.0, "text": "Текст"}]
SUMMARIES = [{"start": 0.0, "end": 2.0, "text": "Конспект", "elapsed_seconds": 0.1}]

SETTINGS = WorkerSettings(
    rabbitmq_password="test",
    s3_access_key="test",
    s3_secret_key="test",
    retry_delay_seconds=0,
    reconnect_delay_seconds=0,
)
INPUT = SETTINGS.transcription_queue
T_DONE = SETTINGS.transcription_completed_queue
S_DONE = SETTINGS.summary_completed_queue
FAILED = SETTINGS.failed_queue


class Models:
    def __init__(self):
        self.transcribe_calls = 0
        self.summarize_calls = 0
        self.threads: set[int] = set()
        self.transcribe_effect = None
        self.gate: threading.Event | None = None

    def transcribe(self, path):
        self.transcribe_calls += 1
        self.threads.add(threading.get_ident())
        if self.gate is not None:
            assert self.gate.wait(10), "тест: задание не было отпущено"
        if self.transcribe_effect is not None:
            return self.transcribe_effect()
        return SEGMENTS

    def summarize(self, segments):
        self.summarize_calls += 1
        self.threads.add(threading.get_ident())
        return SUMMARIES


@pytest.fixture
def broker():
    return FakeBroker()


@pytest.fixture
def models():
    return Models()


@pytest.fixture
def storage():
    storage = FakeStorage()
    storage.add(AUDIO_KEY, b"audio bytes")
    return storage


@pytest.fixture
def make_worker(broker, models, storage, tmp_path):
    def factory(settings=SETTINGS, stop_when_idle=True, **kwargs):
        services = JobServices(
            storage=storage,
            transcribe=models.transcribe,
            summarize=models.summarize,
            transcription_completed_queue=settings.transcription_completed_queue,
            summary_completed_queue=settings.summary_completed_queue,
            work_dir=tmp_path / "work",
        )
        worker = Worker(
            settings, services, connect=broker.connect, poll_seconds=0.005, **kwargs
        )
        if stop_when_idle:

            def stop_if_idle():
                connection = broker.connections[-1]
                busy = any(ch.unacked for ch in connection.channels)
                if not broker.queues[settings.transcription_queue] and not busy:
                    worker.request_stop("тест: очередь пуста")

            broker.on_idle = stop_if_idle
        return worker

    return factory


def failed_messages(broker):
    return [json.loads(body) for body in broker.messages(FAILED)]


def test_success_publishes_both_events_then_acks(broker, make_worker, models):
    broker.send(INPUT, TASK_BODY)
    assert make_worker().run() == 0
    assert broker.actions() == [("publish", T_DONE), ("publish", S_DONE), ("ack", 1, 1)]
    assert json.loads(broker.messages(T_DONE)[0]) == {
        "recordingId": 5,
        "audioObjectKey": AUDIO_KEY,
        "transcriptionObjectKey": "recordings/5/upload/transcription.json",
    }
    assert json.loads(broker.messages(S_DONE)[0]) == {
        "recordingId": 5,
        "audioObjectKey": AUDIO_KEY,
        "summaryObjectKey": "recordings/5/upload/summaries.json",
    }
    for _, body, properties in broker.published:
        assert properties.content_type == "application/json"
        assert properties.content_encoding == "utf-8"
        assert properties.delivery_mode == 2
        body.decode("utf-8")
    assert models.threads and threading.get_ident() not in models.threads
    assert broker.thread_violations == []
    assert broker.messages(INPUT) == []


def test_channel_setup_matches_java_queues(broker, make_worker):
    make_worker().run()
    assert broker.declared == [
        (name, {"durable": True}) for name in (INPUT, T_DONE, S_DONE, FAILED)
    ]
    channel = broker.connections[0].channels[0]
    assert channel.prefetch == 1 and channel.confirm


def test_invalid_json_goes_to_failed_queue_without_models(broker, make_worker):
    process = Mock(side_effect=AssertionError("модели не должны запускаться"))
    broker.send(INPUT, b"{not json")
    make_worker(process=process).run()
    process.assert_not_called()
    assert broker.actions() == [("publish", FAILED), ("ack", 1, 1)]
    [failure] = failed_messages(broker)
    assert failure["stage"] == "validate"
    assert failure["originalMessage"] == "{not json"
    assert failure["attempts"] == 1 and failure["permanent"] is True
    assert "JSON" in failure["error"]
    assert broker.messages(T_DONE) == broker.messages(S_DONE) == []


@pytest.mark.parametrize(
    "body",
    [
        b'{"recordingId": 5}',
        b'{"recordingId": "5", "audioObjectKey": "recordings/5/a.m4a"}',
        b'{"recordingId": 5, "audioObjectKey": "recordings/5/a.exe"}',
    ],
)
def test_invalid_task_fields_are_diagnosed(broker, make_worker, models, body):
    broker.send(INPUT, body)
    make_worker().run()
    [failure] = failed_messages(broker)
    assert (
        failure["stage"] == "validate" and failure["originalMessage"] == body.decode()
    )
    assert models.transcribe_calls == 0


def test_temporary_error_is_retried_with_counter(broker, make_worker, models):
    errors = [ConnectionError("S3 моргнул")]

    def flaky():
        if errors:
            raise errors.pop()
        return SEGMENTS

    models.transcribe_effect = flaky
    broker.send(INPUT, TASK_BODY, pika.BasicProperties(headers={"x-trace": "abc"}))
    make_worker().run()
    assert broker.actions() == [
        ("publish", INPUT),
        ("ack", 1, 1),
        ("publish", T_DONE),
        ("publish", S_DONE),
        ("ack", 1, 2),
    ]
    retry = next(p for q, _, p in broker.published if q == INPUT)
    assert retry.headers[ATTEMPTS_HEADER] == 1
    assert retry.headers["x-trace"] == "abc"
    assert retry.headers["x-lectureflow-last-stage"] == "transcribe"
    assert failed_messages(broker) == []


def test_attempts_are_limited_then_failed_queue(broker, make_worker, models):
    def always_fails():
        raise ConnectionError("S3 недоступен")

    models.transcribe_effect = always_fails
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert models.transcribe_calls == SETTINGS.max_attempts == 3
    assert broker.actions() == [
        ("publish", INPUT),
        ("ack", 1, 1),
        ("publish", INPUT),
        ("ack", 1, 2),
        ("publish", FAILED),
        ("ack", 1, 3),
    ]
    [failure] = failed_messages(broker)
    assert failure["attempts"] == 3 and failure["maxAttempts"] == 3
    assert failure["stage"] == "transcribe" and failure["permanent"] is False
    assert failure["recordingId"] == 5 and failure["audioObjectKey"] == AUDIO_KEY
    assert failure["originalMessage"] == TASK_BODY.decode()
    assert "S3 недоступен" in failure["error"]
    assert broker.messages(INPUT) == []
    assert broker.messages(T_DONE) == []


def test_permanent_error_is_not_retried(broker, make_worker, models):
    models.transcribe_effect = lambda: []
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert models.transcribe_calls == 1
    assert broker.actions() == [("publish", FAILED), ("ack", 1, 1)]
    [failure] = failed_messages(broker)
    assert failure["permanent"] is True and failure["stage"] == "transcribe"


def test_nacked_summary_event_is_not_acked_and_retry_reuses_results(
    broker, make_worker, models
):
    broker.publish_failures[S_DONE] = [NackError([])]
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert broker.actions() == [
        ("publish", T_DONE),
        ("publish", INPUT),
        ("ack", 1, 1),
        ("publish", T_DONE),
        ("publish", S_DONE),
        ("ack", 1, 2),
    ]
    assert models.transcribe_calls == 1 and models.summarize_calls == 1


def test_unroutable_event_is_error(broker, make_worker):
    broker.publish_failures[T_DONE] = [UnroutableError([])]
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert broker.actions()[:2] == [("publish", INPUT), ("ack", 1, 1)]


def test_failed_queue_publish_must_be_confirmed_before_ack(broker, make_worker):
    broker.publish_failures[FAILED] = [NackError([])]
    broker.send(INPUT, b"{not json")
    make_worker().run()
    assert ("ack", 1, 1) not in broker.log
    assert broker.actions() == [("publish", FAILED), ("ack", 2, 1)]
    assert len(failed_messages(broker)) == 1


def test_connection_loss_during_job_never_acks_old_tag(broker, make_worker, models):
    models.gate = threading.Event()

    def on_process(connection):
        if connection.number == 1 and connection.process_calls == 5:
            connection.lose()
            connection.ensure_open()
        if connection.number == 2:
            models.gate.set()

    broker.on_process = on_process
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert [entry for entry in broker.log if entry[0] == "ack"] == [("ack", 2, 1)]
    assert broker.actions() == [("publish", T_DONE), ("publish", S_DONE), ("ack", 2, 1)]

    assert models.transcribe_calls == 1
    assert broker.thread_violations == []


def test_heartbeats_are_served_while_model_runs(broker, make_worker, models):
    models.gate = threading.Event()

    def on_process(connection):
        if connection.process_calls >= 20:
            models.gate.set()

    broker.on_process = on_process
    broker.send(INPUT, TASK_BODY)
    make_worker().run()
    assert broker.connections[0].process_calls >= 20
    assert broker.actions()[-1] == ("ack", 1, 1)
    assert broker.thread_violations == []


def test_stop_during_job_leaves_message_in_queue(broker, make_worker, models):
    models.gate = threading.Event()
    worker = make_worker(stop_when_idle=False)

    def on_process(connection):
        if connection.process_calls == 3:
            worker.request_stop("тест")

    broker.on_process = on_process
    broker.send(INPUT, TASK_BODY)
    try:
        assert worker.run() == 0
        assert worker.job_in_progress
        assert [e for e in broker.log if e[0] == "ack"] == []
        assert broker.messages(INPUT) == [TASK_BODY]
        assert broker.queues[INPUT][0][2] is True
        assert broker.log[-1] == ("close", 1)
        assert len(broker.connections) == 1
    finally:
        models.gate.set()


def test_stop_while_idle_takes_no_new_jobs(broker, make_worker, models):
    worker = make_worker(stop_when_idle=False)
    worker.request_stop("тест")
    broker.send(INPUT, TASK_BODY)
    assert worker.run() == 0
    assert models.transcribe_calls == 0
    assert broker.messages(INPUT) == [TASK_BODY]


def test_old_delivery_tag_cannot_be_acked(broker, make_worker):
    worker = make_worker()
    worker._generation = 2
    worker._channel = Mock()
    with pytest.raises(RuntimeError, match="прежнего соединения"):
        worker._ack(Delivery(1, 1, False, None, TASK_BODY))
    worker._channel.basic_ack.assert_not_called()


def test_signal_requests_stop_then_second_signal_exits(make_worker):
    worker = make_worker()
    worker._on_signal(signal.SIGINT, None)
    assert worker._stop.is_set()
    with pytest.raises(KeyboardInterrupt):
        worker._on_signal(signal.SIGINT, None)
