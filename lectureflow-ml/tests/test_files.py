import json

import pytest

from lectureflow_ml.files import read_segments, save_json


@pytest.mark.parametrize(
    "data", [[], [{"start": 0.0, "end": 1.5, "text": "Русский текст: ё"}]]
)
def test_json_round_trip(tmp_path, data):
    path = tmp_path / "nested" / "transcription.json"
    save_json(path, data)
    assert read_segments(path) == data
    assert "\\u" not in path.read_text(encoding="utf-8")


def test_summary_json_has_timestamps(tmp_path):
    data = [{"start": 0.0, "end": 12.5, "text": "Конспект", "elapsed_seconds": 0.1}]
    path = tmp_path / "summaries.json"
    save_json(path, data)
    assert json.loads(path.read_text(encoding="utf-8")) == data


def test_failed_serialization_keeps_previous_result(tmp_path):
    path = tmp_path / "result.json"
    save_json(path, ["предыдущий результат"])
    with pytest.raises(TypeError):
        save_json(path, [object()])
    assert json.loads(path.read_text(encoding="utf-8")) == ["предыдущий результат"]
    assert list(tmp_path.iterdir()) == [path]


@pytest.mark.parametrize(
    "data",
    [
        {},
        [None],
        [{"start": 2, "end": 1, "text": "x"}],
        [{"start": True, "end": 1, "text": "x"}],
        [{"start": 0, "end": 1}],
        [{"start": 0, "end": float("inf"), "text": "x"}],
    ],
)
def test_invalid_transcription(tmp_path, data):
    path = tmp_path / "bad.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError):
        read_segments(path)
