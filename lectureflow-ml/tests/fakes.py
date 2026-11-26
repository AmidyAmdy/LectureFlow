import hashlib
import threading
import time
from collections import deque
from pathlib import Path
from types import SimpleNamespace

from pika.exceptions import StreamLostError, UnroutableError

from lectureflow_ml.storage import ObjectInfo, ObjectNotFoundError, SourceChangedError


class FakeStorage:
    def __init__(self, events: list | None = None) -> None:
        self.objects: dict[str, tuple[bytes, dict[str, str]]] = {}
        self.events = events if events is not None else []

    def add(self, key: str, data: bytes, metadata: dict | None = None) -> None:
        self.objects[key] = (data, dict(metadata or {}))

    def _info(self, key: str) -> ObjectInfo:
        data, metadata = self.objects[key]
        etag = '"' + hashlib.md5(data).hexdigest() + '"'
        return ObjectInfo(key, len(data), etag, dict(metadata))

    def head(self, key: str) -> ObjectInfo | None:
        self.events.append(("head", key))
        return self._info(key) if key in self.objects else None

    def download(self, key: str, path: Path, *, expected_etag: str) -> ObjectInfo:
        self.events.append(("download", key))
        if key not in self.objects:
            raise ObjectNotFoundError(key)
        info = self._info(key)
        if info.etag != expected_etag:
            raise SourceChangedError(key)
        path.write_bytes(self.objects[key][0])
        return info

    def read_bytes(self, key: str) -> bytes:
        self.events.append(("read", key))
        return self.objects[key][0]

    def put_bytes(self, key: str, data: bytes, *, metadata: dict[str, str]) -> None:
        self.events.append(("put", key))
        self.objects[key] = (data, dict(metadata))


class FakeBroker:
    def __init__(self) -> None:
        self.queues: dict[str, deque] = {}
        self.log: list[tuple] = []
        self.published: list[tuple[str, bytes, object]] = []
        self.declared: list[tuple] = []
        self.connections: list[FakeConnection] = []
        self.publish_failures: dict[str, list[Exception]] = {}
        self.thread_violations: list[str] = []
        self.on_process = None
        self.on_idle = None
        self.owner_thread = threading.get_ident()

    def connect(self) -> "FakeConnection":
        connection = FakeConnection(self, len(self.connections) + 1)
        self.connections.append(connection)
        return connection

    def send(self, queue: str, body: bytes, properties=None) -> None:
        self.queues.setdefault(queue, deque()).append((body, properties, False))

    def check_thread(self, operation: str) -> None:
        if threading.get_ident() != self.owner_thread:
            self.thread_violations.append(operation)
            raise AssertionError(f"pika.{operation} вызван не из главного потока")

    def messages(self, queue: str) -> list[bytes]:
        return [body for body, _, _ in self.queues.get(queue, ())]

    def actions(self) -> list[tuple]:
        return [entry for entry in self.log if entry[0] in ("publish", "ack")]


class FakeConnection:
    def __init__(self, broker: FakeBroker, number: int) -> None:
        self.broker = broker
        self.number = number
        self.is_open = True
        self.channels: list[FakeChannel] = []
        self.process_calls = 0

    def channel(self) -> "FakeChannel":
        self.broker.check_thread("channel")
        channel = FakeChannel(self)
        self.channels.append(channel)
        return channel

    def ensure_open(self) -> None:
        if not self.is_open:
            raise StreamLostError("соединение закрыто")

    def process_data_events(self, time_limit: float = 0) -> None:
        self.broker.check_thread("process_data_events")
        self.ensure_open()
        self.process_calls += 1
        if self.process_calls > 20000:
            raise AssertionError("тест завис: слишком много process_data_events")
        if self.broker.on_process is not None:
            self.broker.on_process(self)
        time.sleep(min(time_limit, 0.002))

    def lose(self) -> None:

        self.is_open = False
        for channel in self.channels:
            channel.requeue_unacked()
        self.broker.log.append(("lost", self.number))

    def close(self) -> None:
        self.broker.check_thread("close")
        self.ensure_open()
        self.is_open = False
        for channel in self.channels:
            channel.requeue_unacked()
        self.broker.log.append(("close", self.number))


class FakeChannel:
    def __init__(self, connection: FakeConnection) -> None:
        self.connection = connection
        self.broker = connection.broker
        self.unacked: dict[int, tuple] = {}
        self.next_tag = 1
        self.confirm = False
        self.prefetch = None

    def confirm_delivery(self) -> None:
        self.broker.check_thread("confirm_delivery")
        self.confirm = True

    def basic_qos(self, prefetch_count: int) -> None:
        self.broker.check_thread("basic_qos")
        self.prefetch = prefetch_count

    def queue_declare(self, queue: str, **kwargs) -> None:
        self.broker.check_thread("queue_declare")
        self.broker.declared.append((queue, kwargs))
        self.broker.queues.setdefault(queue, deque())

    def consume(self, queue: str, inactivity_timeout: float | None = None):
        idle = 0
        while True:
            self.broker.check_thread("consume")
            self.connection.ensure_open()
            pending = self.broker.queues[queue]
            if pending and len(self.unacked) < self.prefetch:
                body, properties, redelivered = pending.popleft()
                tag = self.next_tag
                self.next_tag += 1
                self.unacked[tag] = (queue, body, properties)
                self.broker.log.append(("deliver", self.connection.number, tag))
                method = SimpleNamespace(delivery_tag=tag, redelivered=redelivered)
                yield method, properties, body
                continue
            idle += 1
            if idle > 5000:
                raise AssertionError("тест завис: worker не остановился")
            if self.broker.on_idle is not None:
                self.broker.on_idle()
            time.sleep(0.001)
            yield None, None, None

    def basic_ack(self, delivery_tag: int) -> None:
        self.broker.check_thread("basic_ack")
        self.connection.ensure_open()
        if delivery_tag not in self.unacked:
            raise AssertionError(f"Неизвестный delivery_tag {delivery_tag}")
        del self.unacked[delivery_tag]
        self.broker.log.append(("ack", self.connection.number, delivery_tag))

    def basic_publish(self, exchange, routing_key, body, properties, mandatory):
        self.broker.check_thread("basic_publish")
        self.connection.ensure_open()
        assert exchange == "" and mandatory is True and self.confirm
        failures = self.broker.publish_failures.get(routing_key)
        if failures:
            self.broker.log.append(("publish_failed", routing_key))
            raise failures.pop(0)
        if routing_key not in self.broker.queues:
            raise UnroutableError([])
        self.broker.queues[routing_key].append((body, properties, False))
        self.broker.published.append((routing_key, body, properties))
        self.broker.log.append(("publish", routing_key))

    def requeue_unacked(self) -> None:
        for tag in sorted(self.unacked, reverse=True):
            queue, body, properties = self.unacked[tag]
            self.broker.queues[queue].appendleft((body, properties, True))
        self.unacked.clear()
