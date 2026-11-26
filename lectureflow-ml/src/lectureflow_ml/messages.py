import base64
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import PurePosixPath

from . import settings

ATTEMPTS_HEADER = "x-lectureflow-attempts"
MAX_ERROR_LENGTH = 2000
_MAX_RECORDING_ID = 2**63 - 1
_MAX_KEY_BYTES = 1024


class InvalidTaskError(ValueError):
    pass


@dataclass(frozen=True)
class TranscriptionTask:
    recording_id: int
    audio_object_key: str

    def log_context(self) -> str:
        return f"recordingId={self.recording_id} audioObjectKey={self.audio_object_key}"


def parse_task(body: bytes) -> TranscriptionTask:

    try:
        data = json.loads(body.decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise InvalidTaskError(f"Сообщение не в кодировке UTF-8: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise InvalidTaskError(f"Невалидный JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise InvalidTaskError(
            "Ожидается JSON-объект с полями recordingId и audioObjectKey"
        )
    missing = [
        name for name in ("recordingId", "audioObjectKey") if data.get(name) is None
    ]
    if missing:
        raise InvalidTaskError(f"Нет обязательных полей: {', '.join(missing)}")
    recording_id = data["recordingId"]
    if (
        isinstance(recording_id, bool)
        or not isinstance(recording_id, int)
        or not 0 < recording_id <= _MAX_RECORDING_ID
    ):
        raise InvalidTaskError(
            f"recordingId должен быть положительным целым числом, получено {recording_id!r}"
        )
    key = data["audioObjectKey"]
    validate_audio_key(key)
    return TranscriptionTask(recording_id, key)


def validate_audio_key(key: object) -> None:
    if not isinstance(key, str) or not key.strip():
        raise InvalidTaskError("audioObjectKey должен быть непустой строкой")
    if len(key.encode("utf-8")) > _MAX_KEY_BYTES:
        raise InvalidTaskError("audioObjectKey длиннее 1024 байт")
    if any(ord(char) < 32 or char == "\\" for char in key):
        raise InvalidTaskError("audioObjectKey содержит управляющие символы или '\\'")
    parts = key.split("/")
    if key.startswith("/") or any(part in ("", ".", "..") for part in parts):
        raise InvalidTaskError(f"Недопустимый audioObjectKey: {key!r}")
    path = PurePosixPath(key)
    if path.suffix.lower() not in settings.SUPPORTED_AUDIO_SUFFIXES or not path.stem:
        supported = ", ".join(sorted(settings.SUPPORTED_AUDIO_SUFFIXES))
        raise InvalidTaskError(
            f"Неподдерживаемое расширение аудио в {key!r}; поддерживаются: {supported}"
        )


def transcription_completed(task: TranscriptionTask, transcription_key: str) -> dict:
    return {
        "recordingId": task.recording_id,
        "audioObjectKey": task.audio_object_key,
        "transcriptionObjectKey": transcription_key,
    }


def summary_completed(task: TranscriptionTask, summary_key: str) -> dict:
    return {
        "recordingId": task.recording_id,
        "audioObjectKey": task.audio_object_key,
        "summaryObjectKey": summary_key,
    }


def failure_message(
    body: bytes,
    *,
    stage: str,
    error: str,
    attempts: int,
    max_attempts: int,
    permanent: bool,
) -> dict:

    message: dict = {
        "recordingId": None,
        "audioObjectKey": None,
        "stage": stage,
        "error": truncate(error),
        "permanent": permanent,
        "attempts": attempts,
        "maxAttempts": max_attempts,
        "failedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        message["originalMessageBase64"] = base64.b64encode(body).decode("ascii")
        return message
    message["originalMessage"] = text
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return message
    if isinstance(data, dict):
        for name in ("recordingId", "audioObjectKey"):
            if isinstance(data.get(name), (int, str)) and not isinstance(
                data[name], bool
            ):
                message[name] = data[name]
    return message


def encode_json(payload: dict) -> bytes:
    return json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")


def truncate(text: str, limit: int = MAX_ERROR_LENGTH) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"
