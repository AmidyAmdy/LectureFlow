package ru.filatov.lectureflow.processing.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import ru.filatov.lectureflow.processing.ProcessingService;
import ru.filatov.lectureflow.search.ReindexService;

import tools.jackson.databind.json.JsonMapper;

@Service
public class TranscriptionCompletedListener {
    private final JsonMapper jsonMapper;
    private final ProcessingService processingService;
    private final ReindexService reindexService;

    public TranscriptionCompletedListener(
        JsonMapper jsonMapper, ProcessingService processingService, ReindexService reindexService) {
        this.jsonMapper = jsonMapper;
        this.processingService = processingService;
        this.reindexService = reindexService;
    }

    @RabbitListener(queues = RabbitConfig.TRANSCRIPTION_COMPLETED_QUEUE)
    public void handle(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("Пустое уведомление о транскрипции");
        }
        TranscriptionCompleted event = jsonMapper.readValue(json, TranscriptionCompleted.class);
        if (event.recordingId() == null || isBlank(event.audioObjectKey()) || isBlank(event.transcriptionObjectKey())) {
            throw new InvalidMessageException("В уведомлении о транскрипции нет recordingId, audioObjectKey или transcriptionObjectKey");
        }
        if (!processingService.completeTranscription(event)) {
            return;
        }

        reindexService.indexTranscription(event.recordingId(), event.audioObjectKey(), event.transcriptionObjectKey());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
