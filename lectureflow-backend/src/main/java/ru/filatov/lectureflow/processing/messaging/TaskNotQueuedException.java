package ru.filatov.lectureflow.processing.messaging;

public class TaskNotQueuedException extends RuntimeException {

    public TaskNotQueuedException(Long recordingId, Throwable cause) {
        super("Аудио сохранено, но задание на обработку записи " + recordingId
                + " не отправлено в очередь. Повторите запуск обработки, когда RabbitMQ будет доступен.", cause);
    }
}
