from pathlib import Path

from . import settings
from .files import validate_segments
from .types import Segment


def transcribe_audio(audio_path: Path) -> list[Segment]:
    if not audio_path.is_file():
        raise FileNotFoundError(f"Аудиофайл не найден: {audio_path}")
    import mlx_whisper

    result = mlx_whisper.transcribe(
        audio=str(audio_path),
        path_or_hf_repo=settings.WHISPER_MODEL,
        language=settings.WHISPER_LANGUAGE,
        condition_on_previous_text=settings.WHISPER_CONDITION_ON_PREVIOUS_TEXT,
    )
    return validate_segments(result["segments"])
