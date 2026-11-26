import hashlib
import json
import sys
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from lectureflow_ml import settings
from lectureflow_ml.cli import main
from lectureflow_ml.files import save_json
from lectureflow_ml.prompts import PROMPT_RULES
from lectureflow_ml.summarization import summarize_segments


def response(text="Конспект", reason="stop"):
    return SimpleNamespace(
        done=True, done_reason=reason, message=SimpleNamespace(content=text)
    )


PROMPT_SHA256 = "36cab3f487208c434eef4aac8cd3741a629f46b34c0f9ed30cf77240ce63913f"


def test_original_prompt_unchanged():
    assert hashlib.sha256(PROMPT_RULES.encode("utf-8")).hexdigest() == PROMPT_SHA256


def test_one_client_for_all_parts_and_last_part(monkeypatch):
    client = Mock()
    client.chat.return_value = response()
    factory = Mock(return_value=client)
    monkeypatch.setitem(sys.modules, "ollama", SimpleNamespace(Client=factory))
    segments = [
        {"start": 0, "end": 180, "text": "Начало"},
        {"start": 180, "end": 190, "text": "Последний фрагмент"},
    ]
    result = summarize_segments(segments)
    factory.assert_called_once_with(
        host=settings.OLLAMA_HOST, timeout=settings.OLLAMA_TIMEOUT_SECONDS
    )
    assert client.chat.call_count == 2
    assert [(s["start"], s["end"]) for s in result] == [(0, 180), (180, 190)]
    assert (
        "Последний фрагмент" in client.chat.call_args.kwargs["messages"][1]["content"]
    )
    assert client.chat.call_args.kwargs["model"] == "qwen3.5:9b"


def test_empty_input_does_not_create_client(monkeypatch):
    factory = Mock(side_effect=AssertionError("Не должно быть обращения к модели"))
    monkeypatch.setitem(sys.modules, "ollama", SimpleNamespace(Client=factory))
    assert summarize_segments([]) == []
    assert summarize_segments([{"start": 0, "end": 10, "text": " \n"}]) == []
    factory.assert_not_called()


@pytest.mark.parametrize("answer", [response(reason="length"), response(text=" ")])
def test_bad_llm_response_is_error(answer):
    client = Mock()
    client.chat.return_value = answer
    with pytest.raises(RuntimeError, match="часть 1/1"):
        summarize_segments([{"start": 0, "end": 1, "text": "Текст"}], client=client)


def test_cli_summary_failure_does_not_replace_output(tmp_path, monkeypatch, capsys):
    source, output = tmp_path / "transcription.json", tmp_path / "summary.json"
    save_json(source, [{"start": 0, "end": 1, "text": "Текст"}])
    save_json(output, ["прежний конспект"])
    client = Mock()
    client.chat.side_effect = ConnectionError("Ollama недоступна")
    monkeypatch.setitem(
        sys.modules, "ollama", SimpleNamespace(Client=Mock(return_value=client))
    )
    assert main(["summarize", str(source), "-o", str(output)]) == 1
    assert "Ollama недоступна" in capsys.readouterr().err
    assert json.loads(output.read_text(encoding="utf-8")) == ["прежний конспект"]


def test_cli_transcribe_and_summarize_with_substitutes(tmp_path, monkeypatch):
    audio, transcript, output = (
        tmp_path / name for name in ("sample.wav", "transcript.json", "summary.json")
    )
    audio.write_bytes(b"fake audio")
    whisper = Mock(
        return_value={"segments": [{"start": 0, "end": 1, "text": "Текст", "id": 0}]}
    )
    monkeypatch.setitem(sys.modules, "mlx_whisper", SimpleNamespace(transcribe=whisper))
    assert main(["transcribe", str(audio), "-o", str(transcript)]) == 0
    whisper.assert_called_once_with(
        audio=str(audio),
        path_or_hf_repo=settings.WHISPER_MODEL,
        language="ru",
        condition_on_previous_text=False,
    )
    client = Mock()
    client.chat.return_value = response()
    monkeypatch.setitem(
        sys.modules, "ollama", SimpleNamespace(Client=Mock(return_value=client))
    )
    assert main(["summarize", str(transcript), "-o", str(output)]) == 0
    assert whisper.call_count == 1
    assert json.loads(output.read_text(encoding="utf-8"))[0]["text"] == "Конспект"
    assert json.loads(transcript.read_text(encoding="utf-8"))[0]["text"] == "Текст"
    assert main(["summarize", str(transcript), "-o", str(transcript)]) == 1
