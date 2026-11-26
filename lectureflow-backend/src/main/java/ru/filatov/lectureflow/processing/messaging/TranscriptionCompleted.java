package ru.filatov.lectureflow.processing.messaging;

public record TranscriptionCompleted(Long recordingId, String audioObjectKey, String transcriptionObjectKey) {

}
