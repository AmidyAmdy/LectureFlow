import json
import math
import os
from pathlib import Path
from tempfile import NamedTemporaryFile

from .types import PartSummary, Segment


def validate_segments(data: object) -> list[Segment]:
    if not isinstance(data, list):
        raise ValueError("Транскрипция должна быть JSON-массивом сегментов")
    segments: list[Segment] = []
    previous_start = -1.0
    for index, item in enumerate(data):
        if not isinstance(item, dict):
            raise ValueError(f"Сегмент {index}: ожидается объект start/end/text")
        start, end, text = item.get("start"), item.get("end"), item.get("text")
        if any(
            isinstance(v, bool)
            or not isinstance(v, (float, int))
            or not math.isfinite(v)
            for v in (start, end)
        ):
            raise ValueError(
                f"Сегмент {index}: start/end должны быть конечными числами"
            )
        if start < 0 or end < start or start < previous_start:
            raise ValueError(
                f"Сегмент {index}: неверные таймкоды или порядок сегментов"
            )
        if not isinstance(text, str):
            raise ValueError(f"Сегмент {index}: text должен быть строкой")
        segments.append({"start": float(start), "end": float(end), "text": text})
        previous_start = start
    return segments


def validate_summaries(data: object) -> list[PartSummary]:

    if not isinstance(data, list):
        raise ValueError("Конспекты должны быть JSON-массивом")
    for index, item in enumerate(data):
        if not isinstance(item, dict) or not isinstance(item.get("text"), str):
            raise ValueError(f"Конспект {index}: ожидается объект с текстом")
        if any(
            isinstance(item.get(name), bool)
            or not isinstance(item.get(name), (float, int))
            for name in ("start", "end", "elapsed_seconds")
        ):
            raise ValueError(f"Конспект {index}: start/end/elapsed_seconds — числа")
    return data


def read_segments(path: Path) -> list[Segment]:
    with path.open("r", encoding="utf-8") as file:
        return validate_segments(json.load(file))


def save_json(path: Path, data: object) -> None:

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as file:
            temporary_path = Path(file.name)
            json.dump(data, file, ensure_ascii=False, indent=2, allow_nan=False)
            file.write("\n")
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
