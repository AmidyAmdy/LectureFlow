package ru.filatov.lectureflow.recording;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "recordings")
public class Recording {

    public static final String QUEUE_STAGE = "queue";

    private static final Set<String> SUMMARY_STAGES = Set.of(
            "lookup_summaries", "summarize", "upload_summaries", "publish_summary");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Enumerated(EnumType.STRING)
    private RecordingStatus status;

    @Column(name = "audio_object_key")
    private String audioObjectKey;

    @Column(name = "audio_content_type")
    private String audioContentType;

    @Column(name = "audio_size")
    private Long audioSize;

    @Column(name = "transcription_object_key")
    private String transcriptionObjectKey;

    @Column(name = "summary_object_key")
    private String summaryObjectKey;

    @Column(name = "failure_stage")
    private String failureStage;

    @Column(name = "failure_message")
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected Recording() {
    }

    public Recording(String title) {
        this.title = title;
        this.status = RecordingStatus.NEW;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public RecordingStatus getStatus() {
        return status;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
    }

    public void attachAudio(String key, String contentType, long size) {
        this.audioObjectKey = key;
        this.audioContentType = contentType;
        this.audioSize = size;
        this.transcriptionObjectKey = null;
        this.summaryObjectKey = null;
        clearFailure();
        this.status = RecordingStatus.UPLOADED;
    }

    public String getAudioObjectKey() {
        return audioObjectKey;
    }

    public String getAudioContentType() {
        return audioContentType;
    }

    public Long getAudioSize() {
        return audioSize;
    }

    public void completeTranscription(String key) {
        this.transcriptionObjectKey = key;

        if (status == RecordingStatus.UPLOADED) {
            this.status = RecordingStatus.TRANSCRIBED;
        } else if (status == RecordingStatus.FAILED && !isSummaryStage(failureStage)) {
            this.status = RecordingStatus.TRANSCRIBED;
            clearFailure();
        }
    }

    public String getTranscriptionObjectKey() {
        return transcriptionObjectKey;
    }

    public String getSummaryObjectKey() {
        return summaryObjectKey;
    }

    public void completeSummary(String key) {
        this.summaryObjectKey = key;
        this.status = RecordingStatus.SUMMARIZED;
        clearFailure();
    }

    public boolean failProcessing(String stage, String message) {
        if (status == RecordingStatus.SUMMARIZED || status == RecordingStatus.NEW) {
            return false;
        }
        if (!isSummaryStage(stage) && transcriptionObjectKey != null) {
            return false;
        }
        this.status = RecordingStatus.FAILED;
        this.failureStage = stage;
        this.failureMessage = message;
        return true;
    }

    public void failToQueue(String message) {
        this.status = RecordingStatus.FAILED;
        this.failureStage = QUEUE_STAGE;
        this.failureMessage = message;
    }

    public boolean canRestartProcessing() {
        return audioObjectKey != null
                && status != RecordingStatus.NEW
                && status != RecordingStatus.SUMMARIZED;
    }

    public void restartProcessing() {
        clearFailure();
        if (transcriptionObjectKey != null) {
            this.status = RecordingStatus.TRANSCRIBED;
        } else {
            this.status = RecordingStatus.UPLOADED;
        }
    }

    public String getFailureStage() {
        return failureStage;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    private void clearFailure() {
        this.failureStage = null;
        this.failureMessage = null;
    }

    private static boolean isSummaryStage(String stage) {
        return stage != null && SUMMARY_STAGES.contains(stage);
    }
}
