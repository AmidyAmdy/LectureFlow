import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from .settings import WorkerSettings

JSON_CONTENT_TYPE = "application/json; charset=utf-8"
_NOT_FOUND_CODES = {"404", "NoSuchKey", "NotFound", "NoSuchBucket"}


class ObjectNotFoundError(FileNotFoundError):
    pass


class SourceChangedError(RuntimeError):
    pass


@dataclass(frozen=True)
class ObjectInfo:
    key: str
    size: int
    etag: str
    metadata: dict[str, str] = field(default_factory=dict)


class Storage(Protocol):
    def head(self, key: str) -> ObjectInfo | None: ...

    def download(self, key: str, path: Path, *, expected_etag: str) -> ObjectInfo: ...

    def read_bytes(self, key: str) -> bytes: ...

    def put_bytes(self, key: str, data: bytes, *, metadata: dict[str, str]) -> None: ...


class S3Storage:
    def __init__(self, client: Any, bucket: str) -> None:
        self.client = client
        self.bucket = bucket

    @classmethod
    def from_settings(cls, worker_settings: WorkerSettings) -> "S3Storage":
        import boto3
        from botocore.config import Config

        client = boto3.client(
            "s3",
            endpoint_url=worker_settings.s3_endpoint,
            region_name=worker_settings.s3_region,
            aws_access_key_id=worker_settings.s3_access_key,
            aws_secret_access_key=worker_settings.s3_secret_key,
            config=Config(
                s3={"addressing_style": "path"},
                retries={"max_attempts": 3, "mode": "standard"},
                connect_timeout=10,
                read_timeout=120,
            ),
        )
        return cls(client, worker_settings.s3_bucket)

    def check_bucket(self) -> None:

        self.client.head_bucket(Bucket=self.bucket)

    def head(self, key: str) -> ObjectInfo | None:
        try:
            response = self.client.head_object(Bucket=self.bucket, Key=key)
        except Exception as exc:
            if _is_not_found(exc):
                return None
            raise
        return _object_info(key, response)

    def download(self, key: str, path: Path, *, expected_etag: str) -> ObjectInfo:
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=key)
        except Exception as exc:
            if _is_not_found(exc):
                raise ObjectNotFoundError(
                    f"Объект не найден: {self.bucket}/{key}"
                ) from exc
            raise
        body = response["Body"]
        try:
            info = _object_info(key, response)
            if info.etag != expected_etag:
                raise SourceChangedError(
                    f"Объект {key} изменился во время обработки (ETag {info.etag} вместо {expected_etag})"
                )
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("wb") as file:
                shutil.copyfileobj(body, file, length=1024 * 1024)
        finally:
            body.close()
        if path.stat().st_size != info.size:
            raise OSError(
                f"Скачано {path.stat().st_size} байт из {info.size} для {key}"
            )
        return info

    def read_bytes(self, key: str) -> bytes:
        response = self.client.get_object(Bucket=self.bucket, Key=key)
        with response["Body"] as body:
            return body.read()

    def put_bytes(self, key: str, data: bytes, *, metadata: dict[str, str]) -> None:
        self.client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=data,
            ContentType=JSON_CONTENT_TYPE,
            Metadata=metadata,
        )


def _object_info(key: str, response: dict) -> ObjectInfo:
    return ObjectInfo(
        key=key,
        size=int(response["ContentLength"]),
        etag=str(response["ETag"]),
        metadata={k.lower(): v for k, v in (response.get("Metadata") or {}).items()},
    )


def _is_not_found(exc: Exception) -> bool:
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    code = str(response.get("Error", {}).get("Code", ""))
    status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
    return code in _NOT_FOUND_CODES or status == 404
