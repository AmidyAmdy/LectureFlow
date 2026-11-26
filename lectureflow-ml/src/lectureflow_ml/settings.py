import math
import os
from collections.abc import Mapping
from dataclasses import dataclass, field, fields
from pathlib import Path

WHISPER_MODEL = "mlx-community/whisper-large-v3-turbo"
WHISPER_LANGUAGE = "ru"
WHISPER_CONDITION_ON_PREVIOUS_TEXT = False


OLLAMA_HOST = os.environ.get("LECTUREFLOW_OLLAMA_HOST") or "http://localhost:11434"
OLLAMA_MODEL = "qwen3.5:9b"
OLLAMA_THINK = False
OLLAMA_NUM_CTX = 8192
OLLAMA_NUM_PREDICT = 512
OLLAMA_TIMEOUT_SECONDS = 300.0

CHUNK_SECONDS = 180.0
TRANSCRIPTION_PATH = Path("data/output/transcription.json")
SUMMARIES_PATH = Path("data/output/summaries.json")


SUPPORTED_AUDIO_SUFFIXES = frozenset(
    {".m4a", ".mp3", ".wav", ".ogg", ".oga", ".opus", ".flac", ".aac", ".webm", ".mp4"}
)

ENV_FILE_HINT = "uv run --env-file ../.env lectureflow-ml worker"


@dataclass(frozen=True)
class WorkerSettings:
    rabbitmq_host: str = "127.0.0.1"
    rabbitmq_port: int = 5672
    rabbitmq_username: str = "lectureflow"
    rabbitmq_password: str = field(default="", repr=False)
    rabbitmq_vhost: str = "/"
    rabbitmq_heartbeat_seconds: int = 60

    transcription_queue: str = "lectureflow.transcription"
    transcription_completed_queue: str = "lectureflow.transcription.completed"
    summary_completed_queue: str = "lectureflow.summary.completed"
    failed_queue: str = "lectureflow.transcription.failed"

    max_attempts: int = 3
    retry_delay_seconds: float = 30.0
    reconnect_delay_seconds: float = 5.0

    s3_endpoint: str = "http://127.0.0.1:9000"
    s3_region: str = "us-east-1"
    s3_bucket: str = "lectureflow-audio"
    s3_access_key: str = field(default="", repr=False)
    s3_secret_key: str = field(default="", repr=False)

    work_dir: Path = Path("data/work")

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "WorkerSettings":
        environ = os.environ if environ is None else environ
        values: dict[str, object] = {}
        for item in fields(cls):
            raw = environ.get(ENV_NAMES[item.name], "").strip()
            if raw:
                values[item.name] = _convert(item.name, raw, type(item.default))
        missing = [ENV_NAMES[name] for name in SECRET_FIELDS if not values.get(name)]
        if missing:
            raise ValueError(
                f"Не заданы переменные окружения: {', '.join(missing)}. "
                f"Python сам не читает .env; запустите из lectureflow-ml: {ENV_FILE_HINT}"
            )
        result = cls(**values)
        result.validate()
        return result

    def validate(self) -> None:
        checks = [
            (1 <= self.rabbitmq_port <= 65535, "rabbitmq_port", "от 1 до 65535"),
            (
                self.rabbitmq_heartbeat_seconds >= 1,
                "rabbitmq_heartbeat_seconds",
                ">= 1",
            ),
            (self.max_attempts >= 1, "max_attempts", ">= 1"),
            (_delay_ok(self.retry_delay_seconds), "retry_delay_seconds", ">= 0"),
            (
                _delay_ok(self.reconnect_delay_seconds),
                "reconnect_delay_seconds",
                ">= 0",
            ),
        ]
        for ok, name, rule in checks:
            if not ok:
                raise ValueError(f"{ENV_NAMES[name]}: значение должно быть {rule}")
        queues = [
            self.transcription_queue,
            self.transcription_completed_queue,
            self.summary_completed_queue,
            self.failed_queue,
        ]
        if len(set(queues)) != len(queues):
            raise ValueError("Имена очередей worker должны различаться")

    def describe(self) -> str:

        return (
            f"RabbitMQ {self.rabbitmq_username}@{self.rabbitmq_host}:{self.rabbitmq_port}"
            f" vhost={self.rabbitmq_vhost!r}; очереди: вход={self.transcription_queue},"
            f" транскрипции={self.transcription_completed_queue},"
            f" конспекты={self.summary_completed_queue}, ошибки={self.failed_queue};"
            f" S3 {self.s3_endpoint} бакет={self.s3_bucket};"
            f" попыток={self.max_attempts}; рабочий каталог={self.work_dir}"
        )


ENV_NAMES = {
    "rabbitmq_host": "LECTUREFLOW_RABBITMQ_HOST",
    "rabbitmq_port": "LECTUREFLOW_RABBITMQ_PORT",
    "rabbitmq_username": "LECTUREFLOW_RABBITMQ_USERNAME",
    "rabbitmq_password": "LECTUREFLOW_RABBITMQ_PASSWORD",
    "rabbitmq_vhost": "LECTUREFLOW_RABBITMQ_VHOST",
    "rabbitmq_heartbeat_seconds": "LECTUREFLOW_RABBITMQ_HEARTBEAT_SECONDS",
    "transcription_queue": "LECTUREFLOW_QUEUE_TRANSCRIPTION",
    "transcription_completed_queue": "LECTUREFLOW_QUEUE_TRANSCRIPTION_COMPLETED",
    "summary_completed_queue": "LECTUREFLOW_QUEUE_SUMMARY_COMPLETED",
    "failed_queue": "LECTUREFLOW_QUEUE_FAILED",
    "max_attempts": "LECTUREFLOW_MAX_ATTEMPTS",
    "retry_delay_seconds": "LECTUREFLOW_RETRY_DELAY_SECONDS",
    "reconnect_delay_seconds": "LECTUREFLOW_RECONNECT_DELAY_SECONDS",
    "s3_endpoint": "LECTUREFLOW_S3_ENDPOINT",
    "s3_region": "LECTUREFLOW_S3_REGION",
    "s3_bucket": "LECTUREFLOW_S3_BUCKET",
    "s3_access_key": "LECTUREFLOW_S3_ACCESS_KEY",
    "s3_secret_key": "LECTUREFLOW_S3_SECRET_KEY",
    "work_dir": "LECTUREFLOW_WORK_DIR",
}
SECRET_FIELDS = ("rabbitmq_password", "s3_access_key", "s3_secret_key")


def _delay_ok(seconds: float) -> bool:
    return math.isfinite(seconds) and seconds >= 0


def _convert(name: str, raw: str, kind: type) -> object:
    try:
        if kind is int:
            return int(raw)
        if kind is float:
            return float(raw)
    except ValueError:
        raise ValueError(
            f"{ENV_NAMES[name]}: ожидается число, получено {raw!r}"
        ) from None
    if issubclass(kind, Path):
        return Path(raw).expanduser()
    return raw
