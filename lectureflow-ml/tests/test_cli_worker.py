import logging
import subprocess
import sys

import pytest

from lectureflow_ml import worker
from lectureflow_ml.cli import main

SECRET_NAMES = (
    "LECTUREFLOW_RABBITMQ_PASSWORD",
    "LECTUREFLOW_S3_ACCESS_KEY",
    "LECTUREFLOW_S3_SECRET_KEY",
)


def test_worker_without_settings_fails_before_connecting(monkeypatch, caplog):
    for name in SECRET_NAMES:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setattr(
        worker, "build_services", lambda *a: pytest.fail("не должно быть подключений")
    )
    with caplog.at_level(logging.ERROR):
        assert main(["worker"]) == 1
    assert all(name in caplog.text for name in SECRET_NAMES)
    assert "--env-file ../.env" in caplog.text


def test_worker_reports_unavailable_service_without_secrets(monkeypatch, caplog):
    for name in SECRET_NAMES:
        monkeypatch.setenv(name, f"secret-value-{name}")

    def unavailable(*args):
        raise RuntimeError("Ollama http://localhost:11434: модель недоступна")

    monkeypatch.setattr(worker, "build_services", unavailable)
    with caplog.at_level(logging.INFO):
        assert main(["worker"]) == 1
    assert "модель недоступна" in caplog.text
    assert "secret-value" not in caplog.text


def test_import_and_help_do_not_load_models_or_clients():
    code = (
        "import sys, contextlib, io\n"
        "import lectureflow_ml.worker, lectureflow_ml.job\n"
        "from lectureflow_ml.cli import main\n"
        "for argv in (['--help'], ['worker', '--help']):\n"
        "    with contextlib.redirect_stdout(io.StringIO()):\n"
        "        try: main(argv)\n"
        "        except SystemExit: pass\n"
        "heavy = ('mlx', 'mlx_whisper', 'ollama', 'boto3', 'botocore')\n"
        "print(sorted(m for m in heavy if m in sys.modules))\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    )
    assert result.stdout.strip() == "[]"
