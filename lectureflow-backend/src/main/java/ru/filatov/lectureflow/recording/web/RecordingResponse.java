package ru.filatov.lectureflow.recording.web;

import java.time.Instant;

import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingStatus;

public record RecordingResponse(
        Long id,
        String title,
        RecordingStatus status,
        Instant createdAt,
        boolean audioAvailable,
        boolean transcriptionReady,
        boolean summaryReady,
        String failureStage,
        String failureMessage) {

    public static RecordingResponse from(Recording recording) {
        return new RecordingResponse(
                recording.getId(),
                recording.getTitle(),
                recording.getStatus(),
                recording.getCreatedAt(),
                recording.getAudioObjectKey() != null,
                recording.getTranscriptionObjectKey() != null,
                recording.getSummaryObjectKey() != null,
                recording.getFailureStage(),
                recording.getFailureMessage());
    }
}
