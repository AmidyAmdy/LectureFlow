import time

import pika
from pika.exceptions import ChannelClosedByBroker, NackError, UnroutableError

from . import messages
from .settings import WorkerSettings

APP_ID = "lectureflow-ml"


class PublishError(RuntimeError):
    pass


class QueueConfigError(RuntimeError):
    pass


def connection_parameters(worker_settings: WorkerSettings) -> pika.ConnectionParameters:
    return pika.ConnectionParameters(
        host=worker_settings.rabbitmq_host,
        port=worker_settings.rabbitmq_port,
        virtual_host=worker_settings.rabbitmq_vhost,
        credentials=pika.PlainCredentials(
            worker_settings.rabbitmq_username, worker_settings.rabbitmq_password
        ),
        heartbeat=worker_settings.rabbitmq_heartbeat_seconds,
        blocked_connection_timeout=300,
        connection_attempts=1,
        client_properties={"connection_name": "lectureflow-ml worker"},
    )


def connect(worker_settings: WorkerSettings) -> pika.BlockingConnection:
    return pika.BlockingConnection(connection_parameters(worker_settings))


def worker_queues(worker_settings: WorkerSettings) -> list[str]:
    return [
        worker_settings.transcription_queue,
        worker_settings.transcription_completed_queue,
        worker_settings.summary_completed_queue,
        worker_settings.failed_queue,
    ]


def declare_queues(channel, worker_settings: WorkerSettings) -> None:

    for queue in worker_queues(worker_settings):
        try:
            channel.queue_declare(queue=queue, durable=True)
        except ChannelClosedByBroker as exc:
            if exc.reply_code == 406:
                raise QueueConfigError(
                    f"Очередь {queue} уже существует с другими параметрами: {exc.reply_text}"
                ) from exc
            raise


def open_channel(connection, worker_settings: WorkerSettings):
    channel = connection.channel()
    channel.confirm_delivery()
    channel.basic_qos(prefetch_count=1)
    declare_queues(channel, worker_settings)
    return channel


def publish(
    channel,
    queue: str,
    body: bytes,
    *,
    headers: dict | None = None,
    message_id: str | None = None,
) -> None:

    properties = pika.BasicProperties(
        content_type="application/json",
        content_encoding="utf-8",
        delivery_mode=pika.DeliveryMode.Persistent,
        headers=headers,
        message_id=message_id,
        timestamp=int(time.time()),
        app_id=APP_ID,
    )
    try:
        channel.basic_publish(
            exchange="",
            routing_key=queue,
            body=body,
            properties=properties,
            mandatory=True,
        )
    except UnroutableError as exc:
        raise PublishError(
            f"Очередь {queue} не найдена, сообщение возвращено брокером"
        ) from exc
    except NackError as exc:
        raise PublishError(
            f"Брокер не подтвердил сообщение для очереди {queue} (nack)"
        ) from exc


def publish_json(channel, queue: str, payload: dict, **kwargs) -> None:
    publish(channel, queue, messages.encode_json(payload), **kwargs)
