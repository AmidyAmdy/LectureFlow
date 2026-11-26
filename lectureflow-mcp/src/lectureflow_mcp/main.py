import httpx
from mcp.server import MCPServer
from pydantic import BaseModel


class ChatGPTFile(BaseModel):
    download_url: str
    file_id: str
    mime_type: str = ""
    file_name: str = ""


BACKEND_URL = "http://127.0.0.1:8080"

mcp = MCPServer("LectureFlow")


@mcp.tool(description="Проверяет доступность MCP-сервера LectureFlow.")
def ping() -> str:
    return "LectureFlow MCP работает"


@mcp.tool(description="Возвращает список аудиозаписей LectureFlow и их статусы")
def list_recordings() -> list[dict]:
    response = httpx.get(f"{BACKEND_URL}/recordings", timeout=10.0)
    response.raise_for_status()
    return response.json()


@mcp.tool(description="Возвращает транскрипцию аудиозаписи по её идентификатору")
def get_transcription(recording_id: int) -> list[dict]:
    response = httpx.get(
        f"{BACKEND_URL}/recordings/{recording_id}/transcription", timeout=10.0
    )
    response.raise_for_status()
    return response.json()


@mcp.tool(
    description="Ищет фрагменты аудиозаписей по текстовому запросу и возвращает текст с таймкодами"
)
def search_transcriptions(query: str) -> list[dict]:
    response = httpx.get(
        f"{BACKEND_URL}/search",
        params={"q": query},
        timeout=10.0,
    )
    response.raise_for_status()
    return response.json()


@mcp.tool(description="Создаёт новую запись LectureFlow с указанным названием")
def create_recording(title: str) -> dict:
    response = httpx.post(
        f"{BACKEND_URL}/recordings", json={"title": title}, timeout=10.0
    )
    response.raise_for_status()
    return response.json()


@mcp.tool(
    description="Загружает приложенный пользователем аудиофайл в указанную запись LectureFlow",
    meta={"openai/fileParams": ["file"]},
)
def upload_audio(recording_id: int, file: ChatGPTFile) -> dict:
    response = httpx.get(file.download_url, timeout=60.0, follow_redirects=True)
    response.raise_for_status()
    upload_response = httpx.post(
        f"{BACKEND_URL}/recordings/{recording_id}/audio",
        timeout=60.0,
        files={
            "file": (
                file.file_name or "audio",
                response.content,
                file.mime_type or "application/octet-stream",
            )
        },
    )
    upload_response.raise_for_status()
    return {"recording_id": recording_id, "status": "uploaded"}


@mcp.tool(description="Возвращает сведения о записи и текущий статус обработки")
def get_recording(recording_id: int) -> dict:
    response = httpx.get(f"{BACKEND_URL}/recordings/{recording_id}", timeout=10.0)
    response.raise_for_status()
    return response.json()


def main() -> None:
    mcp.run()
