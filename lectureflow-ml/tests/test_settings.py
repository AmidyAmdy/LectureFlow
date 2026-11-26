from pathlib import Path

import pytest

from lectureflow_ml.settings import ENV_NAMES, SECRET_FIELDS, WorkerSettings

SECRETS = {
    "LECTUREFLOW_RABBITMQ_PASSWORD": "rabbit-secret",
    "LECTUREFLOW_S3_ACCESS_KEY": "s3-access",
    "LECTUREFLOW_S3_SECRET_KEY": "s3-secret",
}


def test_defaults_match_local_setup():
    settings = WorkerSettings.from_env(SECRETS)
    assert (settings.rabbitmq_host, settings.rabbitmq_port) == ("127.0.0.1", 5672)
    assert settings.s3_endpoint == "http://127.0.0.1:9000"
    assert settings.s3_bucket == "lectureflow-audio"
    assert settings.transcription_queue == "lectureflow.transcription"
    assert (
        settings.transcription_completed_queue == "lectureflow.transcription.completed"
    )
    assert settings.summary_completed_queue == "lectureflow.summary.completed"
    assert settings.rabbitmq_password == "rabbit-secret"


@pytest.mark.parametrize("empty", [{}, dict.fromkeys(SECRETS, "  ")])
def test_missing_secrets_list_names_and_hint(empty):
    with pytest.raises(ValueError) as error:
        WorkerSettings.from_env(empty)
    for name in SECRETS:
        assert name in str(error.value)
    assert "--env-file ../.env" in str(error.value)


def test_overrides_and_types():
    settings = WorkerSettings.from_env(
        {
            **SECRETS,
            "LECTUREFLOW_RABBITMQ_PORT": "5673",
            "LECTUREFLOW_MAX_ATTEMPTS": "5",
            "LECTUREFLOW_RETRY_DELAY_SECONDS": "1.5",
            "LECTUREFLOW_QUEUE_FAILED": "test.failed",
            "LECTUREFLOW_WORK_DIR": "/tmp/lf",
        }
    )
    assert settings.rabbitmq_port == 5673
    assert settings.max_attempts == 5
    assert settings.retry_delay_seconds == 1.5
    assert settings.failed_queue == "test.failed"
    assert settings.work_dir == Path("/tmp/lf")


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("LECTUREFLOW_RABBITMQ_PORT", "abc"),
        ("LECTUREFLOW_RABBITMQ_PORT", "0"),
        ("LECTUREFLOW_MAX_ATTEMPTS", "0"),
        ("LECTUREFLOW_RETRY_DELAY_SECONDS", "-1"),
        ("LECTUREFLOW_RETRY_DELAY_SECONDS", "inf"),
        ("LECTUREFLOW_QUEUE_FAILED", "lectureflow.transcription"),
    ],
)
def test_invalid_values_are_rejected(name, value):
    with pytest.raises(ValueError):
        WorkerSettings.from_env({**SECRETS, name: value})


def test_secrets_are_not_logged():
    settings = WorkerSettings.from_env(SECRETS)
    for text in (repr(settings), settings.describe()):
        for secret in SECRETS.values():
            assert secret not in text


def test_env_example_contains_only_empty_credentials():
    example = Path(__file__).resolve().parents[2] / ".env.example"
    variables = dict(line.split("=", 1) for line in example.read_text().splitlines())
    names = {"LECTUREFLOW_DB_PASSWORD", *(ENV_NAMES[field] for field in SECRET_FIELDS)}
    assert variables == dict.fromkeys(names, "")
