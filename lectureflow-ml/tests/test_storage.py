import io

import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber

from lectureflow_ml.settings import WorkerSettings
from lectureflow_ml.storage import (
    JSON_CONTENT_TYPE,
    ObjectNotFoundError,
    S3Storage,
    SourceChangedError,
)

BUCKET = "lectureflow-audio"


@pytest.fixture
def storage():
    settings = WorkerSettings(s3_access_key="test-access", s3_secret_key="test-secret")
    storage = S3Storage.from_settings(settings)
    with Stubber(storage.client) as stubber:
        storage.stubber = stubber
        yield storage
        stubber.assert_no_pending_responses()


def body(data: bytes) -> StreamingBody:
    return StreamingBody(io.BytesIO(data), len(data))


def test_client_uses_local_rustfs_with_path_style(storage):
    assert storage.client.meta.endpoint_url == "http://127.0.0.1:9000"
    assert storage.client.meta.config.s3["addressing_style"] == "path"
    assert storage.bucket == BUCKET


def test_head_missing_object_returns_none(storage):
    storage.stubber.add_client_error(
        "head_object", service_error_code="404", http_status_code=404
    )
    assert storage.head("missing.json") is None


def test_head_returns_size_etag_and_metadata(storage):
    storage.stubber.add_response(
        "head_object",
        {
            "ContentLength": 3,
            "ETag": '"abc"',
            "Metadata": {"lectureflow-fingerprint": "f"},
        },
        {"Bucket": BUCKET, "Key": "k.json"},
    )
    info = storage.head("k.json")
    assert (info.size, info.etag, info.metadata) == (
        3,
        '"abc"',
        {"lectureflow-fingerprint": "f"},
    )


def test_put_bytes_sets_json_content_type_and_metadata(storage):
    storage.stubber.add_response(
        "put_object",
        {},
        {
            "Bucket": BUCKET,
            "Key": "r/transcription.json",
            "Body": b"[]",
            "ContentType": JSON_CONTENT_TYPE,
            "Metadata": {"lectureflow-fingerprint": "f"},
        },
    )
    storage.put_bytes(
        "r/transcription.json", b"[]", metadata={"lectureflow-fingerprint": "f"}
    )


def test_download_writes_file(storage, tmp_path):
    storage.stubber.add_response(
        "get_object",
        {"Body": body(b"audio"), "ContentLength": 5, "ETag": '"e"'},
        {"Bucket": BUCKET, "Key": "a.m4a"},
    )
    path = tmp_path / "job" / "audio.m4a"
    storage.download("a.m4a", path, expected_etag='"e"')
    assert path.read_bytes() == b"audio"


def test_download_rejects_changed_object(storage, tmp_path):
    storage.stubber.add_response(
        "get_object", {"Body": body(b"new"), "ContentLength": 3, "ETag": '"new"'}
    )
    with pytest.raises(SourceChangedError):
        storage.download("a.m4a", tmp_path / "a.m4a", expected_etag='"old"')


def test_download_missing_object(storage, tmp_path):
    storage.stubber.add_client_error(
        "get_object", service_error_code="NoSuchKey", http_status_code=404
    )
    with pytest.raises(ObjectNotFoundError):
        storage.download("a.m4a", tmp_path / "a.m4a", expected_etag='"e"')
