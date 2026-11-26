package ru.filatov.lectureflow.processing.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import ru.filatov.lectureflow.processing.ProcessingService;

import tools.jackson.databind.json.JsonMapper;

@Service
public class SummaryCompletedListener {
    private final JsonMapper jsonMapper;
    private final ProcessingService processingService;

    public SummaryCompletedListener(JsonMapper jsonMapper, ProcessingService processingService) {
        this.jsonMapper = jsonMapper;
        this.processingService = processingService;
    }

    @RabbitListener(queues = RabbitConfig.SUMMARY_COMPLETED_QUEUE)
    public void handle(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidMessageException("Пустое уведомление о конспектах");
        }
        SummaryCompleted summaryCompleted = jsonMapper.readValue(json, SummaryCompleted.class);
        if (summaryCompleted.recordingId() == null || isBlank(summaryCompleted.audioObjectKey())
                || isBlank(summaryCompleted.summaryObjectKey())) {
            throw new InvalidMessageException("В уведомлении о конспектах нет recordingId, audioObjectKey или summaryObjectKey");
        }
        processingService.completeSummary(summaryCompleted);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
