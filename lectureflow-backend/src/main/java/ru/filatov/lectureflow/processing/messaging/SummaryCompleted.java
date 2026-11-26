package ru.filatov.lectureflow.processing.messaging;

public record SummaryCompleted(Long recordingId, String summaryObjectKey, String audioObjectKey) {

}
