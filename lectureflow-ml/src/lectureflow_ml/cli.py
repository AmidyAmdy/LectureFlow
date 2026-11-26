import argparse
import logging
import sys
from pathlib import Path
from time import perf_counter
from typing import TextIO

from . import settings
from .files import read_segments, save_json
from .summarization import summarize_segments
from .transcription import transcribe_audio

WORKER_LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"

QUIET_LOGGERS = (
    "pika",
    "botocore",
    "boto3",
    "s3transfer",
    "urllib3",
    "httpx",
    "httpx2",
    "httpcore",
    "huggingface_hub",
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="LectureFlow ML: транскрипция и конспекты частей"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    transcribe = commands.add_parser(
        "transcribe", help="Распознать аудио и сохранить JSON"
    )
    transcribe.add_argument("input", type=Path, help="Путь к аудиофайлу")
    transcribe.add_argument(
        "-o", "--output", type=Path, default=settings.TRANSCRIPTION_PATH
    )
    summarize = commands.add_parser(
        "summarize", help="Конспектировать сохранённый JSON без Whisper"
    )
    summarize.add_argument("input", type=Path, help="Путь к JSON транскрипции")
    summarize.add_argument("-o", "--output", type=Path, default=settings.SUMMARIES_PATH)
    summarize.add_argument(
        "--chunk-seconds", type=float, default=settings.CHUNK_SECONDS
    )
    commands.add_parser(
        "worker",
        help="Постоянно обрабатывать задания из RabbitMQ",
        description=(
            "Слушает очередь заданий, сохраняет результаты в RustFS и публикует события. "
            "Настройки берутся из переменных окружения (см. .env.example). "
            f"Запуск из каталога lectureflow-ml: {settings.ENV_FILE_HINT}. "
            "Остановка: Ctrl+C."
        ),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "worker":
        _configure_logging(WORKER_LOG_FORMAT, sys.stderr)
        from .worker import run_worker

        try:
            return run_worker()
        except KeyboardInterrupt:
            print("Остановлено до начала обработки заданий", file=sys.stderr)
            return 130

    _configure_logging("%(message)s", sys.stdout)
    started = perf_counter()
    try:
        if args.input.resolve() == args.output.resolve():
            raise ValueError("Входной файл и файл результата должны быть разными")
        if args.command == "transcribe":
            print(f"Распознаю {args.input}...", flush=True)
            result = transcribe_audio(args.input)
        else:
            segments = read_segments(args.input)
            print(f"Прочитано сегментов: {len(segments)}", flush=True)
            result = summarize_segments(segments, chunk_seconds=args.chunk_seconds)
        save_json(args.output, result)
        print(
            f"Сохранено: {args.output} (записей: {len(result)}). Время: {perf_counter() - started:.1f} с."
        )
        return 0
    except Exception as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 1


def _configure_logging(log_format: str, stream: TextIO) -> None:

    logging.basicConfig(level=logging.INFO, format=log_format, stream=stream)
    for name in QUIET_LOGGERS:
        logging.getLogger(name).setLevel(logging.WARNING)
