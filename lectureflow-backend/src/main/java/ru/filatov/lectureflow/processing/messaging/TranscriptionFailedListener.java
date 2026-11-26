package ru.filatov.lectureflow.processing.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import ru.filatov.lectureflow.processing.ProcessingService;

import tools.jackson.databind.json.JsonMapper;

@Service
public class TranscriptionFailedListener {
    private final JsonMapper jsonMapper;
    private final ProcessingService processingService;

    public TranscriptionFailedListener(JsonMapper jsonMapper, ProcessingService processingService) {
        this.jsonMapper = jsonMapper;
        this.processingService = processingService;
    }

    @RabbitListener(queues = RabbitConfig.TRANSCRIPTION_FAILED_QUEUE)
    public void handle(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("Пустое сообщение об ошибке worker");
        }
        TranscriptionFailed event = jsonMapper.readValue(json, TranscriptionFailed.class);

        if (event.recordingId() == null || event.audioObjectKey() == null || event.audioObjectKey().isBlank()) {
            throw new InvalidMessageException("Ошибка worker без recordingId или audioObjectKey: " + event.error());
        }
        processingService.failProcessing(event);
    }
}
