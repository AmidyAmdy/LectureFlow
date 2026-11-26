import base64
import json

import pytest

from lectureflow_ml.messages import (
    InvalidTaskError,
    TranscriptionTask,
    failure_message,
    parse_task,
    summary_completed,
    transcription_completed,
)


def body(**fields):
    return json.dumps(fields).encode("utf-8")


def test_parse_java_task_and_ignore_extra_fields():
    task = parse_task(
        body(recordingId=1, audioObjectKey="recordings/1/abc.m4a", extra="x")
    )
    assert task == TranscriptionTask(1, "recordings/1/abc.m4a")


@pytest.mark.parametrize(
    ("raw", "message"),
    [
        (b"\xff\xfe", "UTF-8"),
        (b"{bad", "JSON"),
        (b"[1, 2]", "JSON-объект"),
        (body(recordingId=1), "audioObjectKey"),
        (body(audioObjectKey="recordings/1/a.m4a"), "recordingId"),
        (body(recordingId=None, audioObjectKey="recordings/1/a.m4a"), "recordingId"),
        (body(recordingId="1", audioObjectKey="recordings/1/a.m4a"), "целым"),
        (body(recordingId=True, audioObjectKey="recordings/1/a.m4a"), "целым"),
        (body(recordingId=1.5, audioObjectKey="recordings/1/a.m4a"), "целым"),
        (body(recordingId=0, audioObjectKey="recordings/1/a.m4a"), "целым"),
        (body(recordingId=1, audioObjectKey=""), "непустой"),
        (body(recordingId=1, audioObjectKey=7), "непустой"),
        (body(recordingId=1, audioObjectKey="/recordings/1/a.m4a"), "Недопустимый"),
        (body(recordingId=1, audioObjectKey="recordings/../a.m4a"), "Недопустимый"),
        (body(recordingId=1, audioObjectKey="recordings//a.m4a"), "Недопустимый"),
        (body(recordingId=1, audioObjectKey="recordings/1/abc"), "расширение"),
        (body(recordingId=1, audioObjectKey="recordings/1/a.txt"), "расширение"),
        (body(recordingId=1, audioObjectKey="recordings/1/.m4a"), "расширение"),
    ],
)
def test_invalid_tasks_have_clear_errors(raw, message):
    with pytest.raises(InvalidTaskError, match=message):
        parse_task(raw)


def test_event_field_names_match_java_records():
    task = TranscriptionTask(3, "recordings/3/x.m4a")
    assert transcription_completed(task, "t.json") == {
        "recordingId": 3,
        "audioObjectKey": "recordings/3/x.m4a",
        "transcriptionObjectKey": "t.json",
    }
    assert summary_completed(task, "s.json") == {
        "recordingId": 3,
        "audioObjectKey": "recordings/3/x.m4a",
        "summaryObjectKey": "s.json",
    }


def test_failure_message_keeps_original_task():
    raw = body(recordingId=2, audioObjectKey="recordings/2/a.m4a")
    message = failure_message(
        raw,
        stage="summarize",
        error="x" * 5000,
        attempts=3,
        max_attempts=3,
        permanent=False,
    )
    assert message["originalMessage"] == raw.decode("utf-8")
    assert message["recordingId"] == 2
    assert message["audioObjectKey"] == "recordings/2/a.m4a"
    assert (message["stage"], message["attempts"], message["maxAttempts"]) == (
        "summarize",
        3,
        3,
    )
    assert len(message["error"]) <= 2000
    assert message["failedAt"].endswith("+00:00")


def test_failure_message_for_binary_body():
    message = failure_message(
        b"\xff", stage="validate", error="e", attempts=1, max_attempts=3, permanent=True
    )
    assert base64.b64decode(message["originalMessageBase64"]) == b"\xff"
    assert message["recordingId"] is None
