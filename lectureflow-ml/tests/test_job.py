import json
import subprocess
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fakes import FakeStorage

from lectureflow_ml import settings
from lectureflow_ml.job import (
    FINGERPRINT_KEY,
    JobError,
    JobServices,
    process_job,
    result_keys,
)
from lectureflow_ml.messages import TranscriptionTask
from lectureflow_ml.rabbit import PublishError
from lectureflow_ml.summarization import summarize_segments

AUDIO_KEY = "recordings/1/abc.m4a"
TRANSCRIPTION_KEY = "recordings/1/abc/transcription.json"
SUMMARIES_KEY = "recordings/1/abc/summaries.json"
SEGMENTS = [{"start": 0.0, "end": 5.0, "text": "Привет, лекция"}]
SUMMARIES = [{"start": 0.0, "end": 5.0, "text": "Конспект", "elapsed_seconds": 0.1}]
TASK = TranscriptionTask(1, AUDIO_KEY)


class Harness:
    def __init__(self, tmp_path):
        self.events: list = []
        self.storage = FakeStorage(self.events)
        self.storage.add(AUDIO_KEY, b"fake audio")
        self.work_dir = tmp_path / "work"
        self.audio_paths: list = []
        self.segments = SEGMENTS
        self.summaries = SUMMARIES

    def transcribe(self, path):
        self.events.append(("transcribe",))
        self.audio_paths.append(path)
        assert path.read_bytes() == self.storage.objects[AUDIO_KEY][0]
        return self.segments

    def summarize(self, segments):
        self.events.append(("summarize",))
        assert segments == self.segments
        return self.summaries

    def publish(self, queue, payload):
        self.events.append(("publish", queue, payload))

    def services(self, **overrides):
        values = dict(
            storage=self.storage,
            transcribe=self.transcribe,
            summarize=self.summarize,
            transcription_completed_queue="t.completed",
            summary_completed_queue="s.completed",
            work_dir=self.work_dir,
        )
        values.update(overrides)
        return JobServices(**values)

    def run(self, publish=None, **overrides):
        return process_job(TASK, self.services(**overrides), publish or self.publish)

    def kinds(self):
        return [event[0] for event in self.events]


@pytest.fixture
def harness(tmp_path):
    return Harness(tmp_path)


@pytest.mark.parametrize(
    ("audio", "base"),
    [
        ("recordings/1/abc.m4a", "recordings/1/abc"),
        ("recordings/7/a.b.mp3", "recordings/7/a.b"),
        ("e2e/2/x.M4A", "e2e/2/x"),
    ],
)
def test_result_keys_are_derived_from_audio_key(audio, base):
    keys = result_keys(audio)
    assert keys.transcription == f"{base}/transcription.json"
    assert keys.summaries == f"{base}/summaries.json"


def test_full_job_uploads_before_each_publish(harness):
    result = harness.run()
    assert harness.events == [
        ("head", AUDIO_KEY),
        ("head", TRANSCRIPTION_KEY),
        ("download", AUDIO_KEY),
        ("transcribe",),
        ("put", TRANSCRIPTION_KEY),
        (
            "publish",
            "t.completed",
            {
                "recordingId": 1,
                "audioObjectKey": AUDIO_KEY,
                "transcriptionObjectKey": TRANSCRIPTION_KEY,
            },
        ),
        ("head", SUMMARIES_KEY),
        ("summarize",),
        ("put", SUMMARIES_KEY),
        (
            "publish",
            "s.completed",
            {
                "recordingId": 1,
                "audioObjectKey": AUDIO_KEY,
                "summaryObjectKey": SUMMARIES_KEY,
            },
        ),
    ]
    assert not result.reused_transcription and not result.reused_summaries
    stored = harness.storage.objects
    assert json.loads(stored[TRANSCRIPTION_KEY][0]) == SEGMENTS
    assert json.loads(stored[SUMMARIES_KEY][0]) == SUMMARIES
    assert "Привет" in stored[TRANSCRIPTION_KEY][0].decode("utf-8")
    assert stored[TRANSCRIPTION_KEY][1]["lectureflow-recording-id"] == "1"
    assert list(harness.work_dir.iterdir()) == []


def test_each_delivery_gets_unique_work_dir(harness):
    harness.run()
    harness.storage.objects.pop(TRANSCRIPTION_KEY)
    harness.run()
    first, second = harness.audio_paths
    assert first.parent != second.parent
    assert first.parent.parent == harness.work_dir
    assert first.parent.name.startswith("recording-1-")
    assert list(harness.work_dir.iterdir()) == []


def test_redelivery_reuses_matching_results(harness):
    first = harness.run()
    harness.events.clear()
    second = harness.run()
    assert second.keys == first.keys
    assert second.reused_transcription and second.reused_summaries
    assert "transcribe" not in harness.kinds()
    assert "summarize" not in harness.kinds()
    assert "download" not in harness.kinds()
    assert "put" not in harness.kinds()
    assert [e[1] for e in harness.events if e[0] == "publish"] == [
        "t.completed",
        "s.completed",
    ]


def test_changed_audio_recomputes_everything(harness):
    harness.run()
    harness.storage.add(AUDIO_KEY, b"other audio with same key")
    harness.events.clear()
    result = harness.run()
    assert not result.reused_transcription and not result.reused_summaries
    assert harness.kinds().count("transcribe") == 1
    assert harness.kinds().count("summarize") == 1


def test_changed_summary_settings_recompute_only_summaries(harness, monkeypatch):
    harness.run()
    monkeypatch.setattr(settings, "OLLAMA_NUM_PREDICT", 1024)
    harness.events.clear()
    result = harness.run()
    assert result.reused_transcription and not result.reused_summaries
    assert "transcribe" not in harness.kinds()
    assert harness.kinds().count("summarize") == 1


def test_changed_whisper_settings_recompute_transcription(harness, monkeypatch):
    harness.run()
    monkeypatch.setattr(settings, "WHISPER_LANGUAGE", "en")
    harness.events.clear()
    assert not harness.run().reused_transcription


def test_foreign_result_without_fingerprint_is_overwritten(harness):
    harness.storage.add(TRANSCRIPTION_KEY, b"[]", metadata={})
    result = harness.run()
    assert not result.reused_transcription
    assert json.loads(harness.storage.objects[TRANSCRIPTION_KEY][0]) == SEGMENTS
    assert FINGERPRINT_KEY in harness.storage.objects[TRANSCRIPTION_KEY][1]


def test_corrupted_result_with_matching_fingerprint_is_recomputed(harness):
    harness.run()
    _, metadata = harness.storage.objects[TRANSCRIPTION_KEY]
    harness.storage.add(TRANSCRIPTION_KEY, b"{broken", metadata=metadata)
    harness.events.clear()
    assert not harness.run().reused_transcription
    assert harness.kinds().count("transcribe") == 1


@pytest.mark.parametrize("segments", [[], [{"start": 0.0, "end": 1.0, "text": "  "}]])
def test_empty_transcription_is_permanent_error(harness, segments):
    harness.segments = segments
    with pytest.raises(JobError) as error:
        harness.run()
    assert error.value.stage == "transcribe"
    assert error.value.permanent
    assert "пустая" in str(error.value)
    assert "put" not in harness.kinds() and "publish" not in harness.kinds()
    assert list(harness.work_dir.iterdir()) == []


def test_truncated_llm_answer_is_clear_temporary_error(harness):
    client = Mock()
    client.chat.return_value = SimpleNamespace(
        done=True, done_reason="length", message=SimpleNamespace(content="обрыв")
    )
    harness.summarize = lambda segments: summarize_segments(segments, client=client)
    with pytest.raises(JobError) as error:
        harness.run()
    assert error.value.stage == "summarize"
    assert not error.value.permanent
    assert "length" in str(error.value)
    assert SUMMARIES_KEY not in harness.storage.objects
    published = [e[1] for e in harness.events if e[0] == "publish"]
    assert published == ["t.completed"]


def test_empty_summary_result_is_error(harness):
    harness.summaries = []
    with pytest.raises(JobError) as error:
        harness.run()
    assert error.value.stage == "summarize"
    assert SUMMARIES_KEY not in harness.storage.objects


def test_missing_audio_is_permanent_error(harness):
    harness.storage.objects.clear()
    with pytest.raises(JobError) as error:
        harness.run()
    assert (error.value.stage, error.value.permanent) == ("source", True)
    assert "transcribe" not in harness.kinds()


def test_undecodable_audio_is_permanent_error(harness):
    def broken(path):
        failure = subprocess.CalledProcessError(
            1, ["ffmpeg"], stderr=b"x\nInvalid data"
        )
        raise RuntimeError("Failed to load audio") from failure

    harness.transcribe = broken
    with pytest.raises(JobError) as error:
        harness.run()
    assert error.value.permanent
    assert "FFmpeg" in str(error.value) and "Invalid data" in str(error.value)


def test_unavailable_model_is_temporary_error(harness):
    def unavailable(segments):
        raise ConnectionError("Ollama недоступна")

    harness.summarize = unavailable
    with pytest.raises(JobError) as error:
        harness.run()
    assert (error.value.stage, error.value.permanent) == ("summarize", False)


def test_publish_failure_stops_job_before_summaries(harness):
    def failing_publish(queue, payload):
        raise PublishError("nack")

    with pytest.raises(JobError) as error:
        harness.run(publish=failing_publish)
    assert (error.value.stage, error.value.permanent) == (
        "publish_transcription",
        False,
    )
    assert "summarize" not in harness.kinds()
    assert TRANSCRIPTION_KEY in harness.storage.objects
