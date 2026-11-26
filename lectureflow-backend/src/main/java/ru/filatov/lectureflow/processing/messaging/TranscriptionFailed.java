package ru.filatov.lectureflow.processing.messaging;

public record TranscriptionFailed(Long recordingId, String audioObjectKey, String stage, String error) {
}
