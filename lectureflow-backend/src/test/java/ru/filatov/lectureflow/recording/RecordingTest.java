package ru.filatov.lectureflow.recording;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ru.filatov.lectureflow.storage.AudioStorageService;

class RecordingTest {

    private static final String AUDIO = "recordings/1/a.m4a";
    private static final String TRANSCRIPTION = "recordings/1/a/transcription.json";
    private static final String SUMMARY = "recordings/1/a/summaries.json";

    private Recording uploaded() {
        Recording recording = new Recording("Лекция");
        recording.attachAudio(AUDIO, "audio/mp4", 100);
        return recording;
    }

    @Test
    void normalOrder() {
        Recording recording = uploaded();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.UPLOADED);
        recording.completeTranscription(TRANSCRIPTION);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.TRANSCRIBED);
        recording.completeSummary(SUMMARY);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
    }

    @Test
    void lateDuplicateTranscriptionDoesNotRollBackSummary() {
        Recording recording = uploaded();
        recording.completeTranscription(TRANSCRIPTION);
        recording.completeSummary(SUMMARY);
        recording.completeTranscription(TRANSCRIPTION);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
    }

    @Test
    void summaryBeforeTranscriptionKeepsBothResults() {
        Recording recording = uploaded();
        recording.completeSummary(SUMMARY);
        recording.completeTranscription(TRANSCRIPTION);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
        assertThat(recording.getTranscriptionObjectKey()).isEqualTo(TRANSCRIPTION);
        assertThat(recording.getSummaryObjectKey()).isEqualTo(SUMMARY);
    }

    @Test
    void summaryFailureKeepsTranscription() {
        Recording recording = uploaded();
        recording.completeTranscription(TRANSCRIPTION);
        assertThat(recording.failProcessing("summarize", "Ollama недоступна")).isTrue();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.FAILED);
        assertThat(recording.getTranscriptionObjectKey()).isEqualTo(TRANSCRIPTION);
        assertThat(recording.getFailureMessage()).isEqualTo("Ollama недоступна");
    }

    @Test
    void summaryFailureBeforeTranscriptionEventStaysFailed() {
        Recording recording = uploaded();
        recording.failProcessing("summarize", "Ollama недоступна");
        recording.completeTranscription(TRANSCRIPTION);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.FAILED);
        assertThat(recording.getTranscriptionObjectKey()).isEqualTo(TRANSCRIPTION);
    }

    @Test
    void outdatedFailuresAreIgnored() {
        Recording recording = uploaded();
        recording.completeTranscription(TRANSCRIPTION);

        assertThat(recording.failProcessing("transcribe", "старая ошибка")).isFalse();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.TRANSCRIBED);

        recording.completeSummary(SUMMARY);
        assertThat(recording.failProcessing("summarize", "старая ошибка")).isFalse();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
    }

    @Test
    void restartKeepsReadyTranscription() {
        Recording recording = uploaded();
        recording.completeTranscription(TRANSCRIPTION);
        recording.failProcessing("summarize", "Ollama недоступна");
        assertThat(recording.canRestartProcessing()).isTrue();

        recording.restartProcessing();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.TRANSCRIBED);
        assertThat(recording.getFailureStage()).isNull();
        assertThat(recording.getTranscriptionObjectKey()).isEqualTo(TRANSCRIPTION);
    }

    @Test
    void restartIsNotAllowedWithoutAudioOrAfterSuccess() {
        assertThat(new Recording("Без аудио").canRestartProcessing()).isFalse();
        Recording recording = uploaded();
        recording.completeSummary(SUMMARY);
        assertThat(recording.canRestartProcessing()).isFalse();
    }

    @Test
    void newAudioResetsResultsAndError() {
        Recording recording = uploaded();
        recording.completeTranscription(TRANSCRIPTION);
        recording.failProcessing("summarize", "ошибка");
        recording.attachAudio("recordings/1/b.mp3", "audio/mpeg", 200);
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(recording.getTranscriptionObjectKey()).isNull();
        assertThat(recording.getSummaryObjectKey()).isNull();
        assertThat(recording.getFailureStage()).isNull();
    }

    @Test
    void resultKeysAreDerivedLikeInWorker() {
        assertThat(AudioStorageService.objectKeysOfAudio("recordings/1/abc.m4a")).containsExactly(
                "recordings/1/abc.m4a", "recordings/1/abc/transcription.json", "recordings/1/abc/summaries.json");
    }
}
