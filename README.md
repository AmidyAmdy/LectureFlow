# Сервис для обработки аудио

Локальный сервис для расшифровки записей встреч, созвонов, интервью и лекций. Он делает конспект, позволяет искать по тексту и переходить к нужному месту в аудио.

- `lectureflow-backend` — Spring Boot, API и веб-интерфейс.
- `lectureflow-ml` — расшифровка через MLX Whisper, конспекты через Ollama.
- `lectureflow-mcp` — доступ к записям из MCP-клиента.

## Запуск

Для обработки аудио нужен Mac с Apple Silicon. Установите Docker Compose, FFmpeg, uv и Ollama. Запустите Ollama.

```sh
cp .env.example .env
```

Заполните четыре поля в `.env` своими значениями и выполните команды из корня проекта.

```sh
docker compose up -d --build --wait
ollama pull qwen3.5:9b
cd lectureflow-ml
uv run --locked --env-file ../.env lectureflow-ml worker
```

Откройте [localhost:8080](http://localhost:8080) и загрузите аудио. При первой обработке Whisper скачает модель.

Команда для MCP-клиента из корня проекта — `uv run --locked --directory lectureflow-mcp lectureflow-mcp`. Сервер работает через stdio.
