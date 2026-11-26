import hashlib
import json
import logging
import shutil
import subprocess
import tempfile
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from time import perf_counter
from typing import TypeVar

from . import messages, settings
from .files import save_json, validate_segments, validate_summaries
from .messages import TranscriptionTask
from .prompts import PROMPT_RULES
from .storage import ObjectInfo, ObjectNotFoundError, Storage
from .types import PartSummary, Segment

logger = logging.getLogger(__name__)

RESULT_FORMAT_VERSION = 1
FINGERPRINT_KEY = "lectureflow-fingerprint"


Publish = Callable[[str, dict], None]
T = TypeVar("T")


class PermanentJobError(Exception):
    pass


class JobError(Exception):
    def __init__(self, stage: str, message: str, *, permanent: bool) -> None:
        super().__init__(message)
        self.stage = stage
        self.permanent = permanent


@dataclass(frozen=True)
class ResultKeys:
    transcription: str
    summaries: str


@dataclass(frozen=True)
class JobServices:
    storage: Storage
    transcribe: Callable[[Path], list[Segment]]
    summarize: Callable[[list[Segment]], list[PartSummary]]
    transcription_completed_queue: str
    summary_completed_queue: str
    work_dir: Path


@dataclass(frozen=True)
class JobResult:
    keys: ResultKeys
    reused_transcription: bool
    reused_summaries: bool


def result_keys(audio_object_key: str) -> ResultKeys:

    base = PurePosixPath(audio_object_key).with_suffix("").as_posix()
    return ResultKeys(f"{base}/transcription.json", f"{base}/summaries.json")


def process_job(
    task: TranscriptionTask, services: JobServices, publish: Publish
) -> JobResult:
    keys = result_keys(task.audio_object_key)
    storage = services.storage
    services.work_dir.mkdir(parents=True, exist_ok=True)

    job_dir = Path(
        tempfile.mkdtemp(
            prefix=f"recording-{task.recording_id}-", dir=services.work_dir
        )
    )
    try:
        with _stage(task, "source"):
            source = storage.head(task.audio_object_key)
            if source is None:
                raise PermanentJobError(
                    f"Исходное аудио не найдено в S3: {task.audio_object_key}"
                )
            if source.size == 0:
                raise PermanentJobError("Исходное аудио пустое (0 байт)")

        fingerprint = transcription_fingerprint(task, source)
        with _stage(task, "lookup_transcription"):
            stored = _load_reusable(
                storage, keys.transcription, fingerprint, _parse_transcription, task
            )
        if stored is not None:
            segments, transcription_bytes = stored
        else:
            audio_path = (
                job_dir / f"audio{PurePosixPath(task.audio_object_key).suffix.lower()}"
            )
            with _stage(task, "download"):
                storage.download(
                    task.audio_object_key, audio_path, expected_etag=source.etag
                )
            with _stage(task, "transcribe"):
                segments = services.transcribe(audio_path)
                _require_speech(segments)
                logger.info(
                    "%s: распознано сегментов: %d", task.log_context(), len(segments)
                )
            audio_path.unlink(missing_ok=True)
            with _stage(task, "upload_transcription"):
                transcription_bytes = _save_and_upload(
                    storage,
                    job_dir / "transcription.json",
                    keys.transcription,
                    segments,
                    _metadata(task, source, fingerprint),
                )
        with _stage(task, "publish_transcription"):
            publish(
                services.transcription_completed_queue,
                messages.transcription_completed(task, keys.transcription),
            )

        transcription_sha256 = hashlib.sha256(transcription_bytes).hexdigest()
        fingerprint = summaries_fingerprint(task, source, transcription_sha256)
        with _stage(task, "lookup_summaries"):
            stored_summaries = _load_reusable(
                storage, keys.summaries, fingerprint, _parse_summaries, task
            )
        if stored_summaries is None:
            with _stage(task, "summarize"):
                summaries = services.summarize(segments)
                if not summaries:
                    raise PermanentJobError("Конспектирование вернуло пустой результат")
            with _stage(task, "upload_summaries"):
                _save_and_upload(
                    storage,
                    job_dir / "summaries.json",
                    keys.summaries,
                    summaries,
                    _metadata(task, source, fingerprint),
                )
        with _stage(task, "publish_summary"):
            publish(
                services.summary_completed_queue,
                messages.summary_completed(task, keys.summaries),
            )
        return JobResult(keys, stored is not None, stored_summaries is not None)
    finally:
        shutil.rmtree(job_dir, ignore_errors=True)


def transcription_fingerprint(task: TranscriptionTask, source: ObjectInfo) -> str:
    return _fingerprint(
        {
            "result": "transcription",
            "format": RESULT_FORMAT_VERSION,
            **_source_fields(task, source),
            "whisperModel": settings.WHISPER_MODEL,
            "whisperLanguage": settings.WHISPER_LANGUAGE,
            "conditionOnPreviousText": settings.WHISPER_CONDITION_ON_PREVIOUS_TEXT,
        }
    )


def summaries_fingerprint(
    task: TranscriptionTask, source: ObjectInfo, transcription_sha256: str
) -> str:
    return _fingerprint(
        {
            "result": "summaries",
            "format": RESULT_FORMAT_VERSION,
            **_source_fields(task, source),
            "transcriptionSha256": transcription_sha256,
            "ollamaModel": settings.OLLAMA_MODEL,
            "ollamaThink": settings.OLLAMA_THINK,
            "numCtx": settings.OLLAMA_NUM_CTX,
            "numPredict": settings.OLLAMA_NUM_PREDICT,
            "chunkSeconds": settings.CHUNK_SECONDS,
            "promptSha256": hashlib.sha256(PROMPT_RULES.encode("utf-8")).hexdigest(),
        }
    )


def describe_error(exc: BaseException) -> str:
    cause = exc.__cause__
    if isinstance(exc, RuntimeError) and isinstance(
        cause, subprocess.CalledProcessError
    ):
        stderr = cause.stderr.decode("utf-8", "replace") if cause.stderr else ""
        tail = " | ".join(line for line in stderr.strip().splitlines()[-3:] if line)
        return f"FFmpeg не смог декодировать аудио: {tail or cause}"
    return messages.truncate(f"{type(exc).__name__}: {exc}")


def is_permanent(exc: BaseException) -> bool:
    if isinstance(
        exc, (PermanentJobError, ObjectNotFoundError, messages.InvalidTaskError)
    ):
        return True

    return isinstance(exc, RuntimeError) and isinstance(
        exc.__cause__, subprocess.CalledProcessError
    )


@contextmanager
def _stage(task: TranscriptionTask, name: str) -> Iterator[None]:
    started = perf_counter()
    logger.info("%s этап=%s: начало", task.log_context(), name)
    try:
        yield
    except Exception as exc:
        permanent = is_permanent(exc)
        description = describe_error(exc)
        logger.error(
            "%s этап=%s: %s ошибка через %.1f с: %s",
            task.log_context(),
            name,
            "постоянная" if permanent else "временная",
            perf_counter() - started,
            description,
        )
        raise JobError(name, description, permanent=permanent) from exc
    logger.info(
        "%s этап=%s: готово за %.1f с",
        task.log_context(),
        name,
        perf_counter() - started,
    )


def _load_reusable(
    storage: Storage,
    key: str,
    fingerprint: str,
    parse: Callable[[bytes], T],
    task: TranscriptionTask,
) -> tuple[T, bytes] | None:
    info = storage.head(key)
    if info is None:
        return None
    if info.metadata.get(FINGERPRINT_KEY) != fingerprint:
        logger.info(
            "%s: %s получен для другого аудио или настроек, пересчитываю",
            task.log_context(),
            key,
        )
        return None
    raw = storage.read_bytes(key)
    try:
        parsed = parse(raw)
    except (ValueError, PermanentJobError) as exc:
        logger.warning(
            "%s: %s повреждён (%s), пересчитываю", task.log_context(), key, exc
        )
        return None
    logger.info("%s: использую сохранённый результат %s", task.log_context(), key)
    return parsed, raw


def _parse_transcription(raw: bytes) -> list[Segment]:
    segments = validate_segments(json.loads(raw.decode("utf-8")))
    _require_speech(segments)
    return segments


def _parse_summaries(raw: bytes) -> list[PartSummary]:
    summaries = validate_summaries(json.loads(raw.decode("utf-8")))
    if not summaries:
        raise ValueError("пустой список конспектов")
    return summaries


def _require_speech(segments: list[Segment]) -> None:
    if not any(segment["text"].strip() for segment in segments):
        raise PermanentJobError("Whisper не распознал речь: транскрипция пустая")


def _save_and_upload(
    storage: Storage, path: Path, key: str, data: object, metadata: dict[str, str]
) -> bytes:
    save_json(path, data)
    raw = path.read_bytes()
    storage.put_bytes(key, raw, metadata=metadata)
    return raw


def _metadata(
    task: TranscriptionTask, source: ObjectInfo, fingerprint: str
) -> dict[str, str]:
    return {
        FINGERPRINT_KEY: fingerprint,
        "lectureflow-recording-id": str(task.recording_id),
        "lectureflow-source-etag": source.etag.strip('"'),
    }


def _source_fields(task: TranscriptionTask, source: ObjectInfo) -> dict:
    return {
        "audioObjectKey": task.audio_object_key,
        "audioETag": source.etag,
        "audioSize": source.size,
    }


def _fingerprint(data: dict) -> str:
    encoded = json.dumps(data, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
