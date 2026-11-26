import math

from . import settings
from .types import Segment


def split_segments(
    segments: list[Segment],
    chunk_seconds: float = settings.CHUNK_SECONDS,
) -> list[list[Segment]]:
    if not math.isfinite(chunk_seconds) or chunk_seconds <= 0:
        raise ValueError("Длительность части должна быть конечным числом больше нуля")

    chunks: list[list[Segment]] = []
    current_chunk: list[Segment] = []
    next_boundary = chunk_seconds
    for segment in segments:
        current_chunk.append(segment)
        if segment["end"] >= next_boundary:
            chunks.append(current_chunk)
            current_chunk = []

            while next_boundary <= segment["end"]:
                next_boundary += chunk_seconds
    if current_chunk:
        chunks.append(current_chunk)
    return chunks
