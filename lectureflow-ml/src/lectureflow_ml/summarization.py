import logging
from time import perf_counter
from typing import TYPE_CHECKING

from . import settings
from .chunks import split_segments
from .prompts import PROMPT_RULES
from .types import PartSummary, Segment

if TYPE_CHECKING:
    from ollama import Client

logger = logging.getLogger(__name__)


def format_time(seconds: float) -> str:
    minutes, remainder = divmod(int(seconds), 60)
    return f"{minutes:02d}:{remainder:02d}"


def format_transcript(chunk: list[Segment]) -> str:
    return "\n".join(
        f"[{format_time(segment['start'])}–{format_time(segment['end'])}] {segment['text'].strip()}"
        for segment in chunk
    )


def summarize_chunk(chunk: list[Segment], client: "Client") -> str:
    if not chunk or not any(segment["text"].strip() for segment in chunk):
        raise ValueError("Нельзя конспектировать пустую часть")
    response = client.chat(
        model=settings.OLLAMA_MODEL,
        messages=[
            {"role": "system", "content": PROMPT_RULES},
            {
                "role": "user",
                "content": f"<transcript>\n{format_transcript(chunk)}\n</transcript>",
            },
        ],
        think=settings.OLLAMA_THINK,
        options={
            "num_ctx": settings.OLLAMA_NUM_CTX,
            "num_predict": settings.OLLAMA_NUM_PREDICT,
        },
    )
    if response.done is not True or response.done_reason != "stop":
        raise RuntimeError(
            f"Ollama не завершила ответ успешно (причина: {response.done_reason}); возможно, исчерпан лимит генерации"
        )
    text = response.message.content
    if not text or not text.strip():
        raise RuntimeError("Ollama вернула пустой конспект")
    return text.strip()


def summarize_segments(
    segments: list[Segment],
    chunk_seconds: float = settings.CHUNK_SECONDS,
    client: "Client | None" = None,
) -> list[PartSummary]:
    chunks = split_segments(segments, chunk_seconds)
    summaries: list[PartSummary] = []
    for number, chunk in enumerate(chunks, start=1):
        if not any(segment["text"].strip() for segment in chunk):
            logger.info(
                "Часть %d/%d: нет текста, пропускаю запрос к LLM.", number, len(chunks)
            )
            continue
        if client is None:
            from ollama import Client

            client = Client(
                host=settings.OLLAMA_HOST, timeout=settings.OLLAMA_TIMEOUT_SECONDS
            )
        start, end = chunk[0]["start"], chunk[-1]["end"]
        logger.info(
            "Часть %d/%d (%s–%s, сегментов: %d)...",
            number,
            len(chunks),
            format_time(start),
            format_time(end),
            len(chunk),
        )
        started = perf_counter()
        try:
            text = summarize_chunk(chunk, client)
        except Exception as exc:
            raise RuntimeError(
                f"Не удалось обработать часть {number}/{len(chunks)}: {exc}"
            ) from exc
        elapsed = perf_counter() - started
        summaries.append(
            {"start": start, "end": end, "text": text, "elapsed_seconds": elapsed}
        )
        logger.info("Готово за %.1f с:\n%s", elapsed, text)
    return summaries
