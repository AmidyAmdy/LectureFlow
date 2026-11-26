package ru.filatov.lectureflow.search;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingRepository;
import ru.filatov.lectureflow.storage.AudioStorageService;

class ReindexServiceTest {

    private final RecordingRepository recordingRepository = mock(RecordingRepository.class);
    private final AudioStorageService audioStorageService = mock(AudioStorageService.class);
    private final SearchService searchService = mock(SearchService.class);
    private final ReindexService reindexService = new ReindexService(recordingRepository, audioStorageService, searchService);

    @Test
    void segmentsOfReplacedAudioAreRemovedAfterIndexing() {
        Recording recording = new Recording("Лекция");
        recording.attachAudio("recordings/1/new.m4a", "audio/mp4", 10);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        when(audioStorageService.readText("recordings/1/old/transcription.json")).thenReturn("[]");

        reindexService.indexTranscription(1L, "recordings/1/old.m4a", "recordings/1/old/transcription.json");

        verify(searchService).deleteByAudioKeyQuietly("recordings/1/old.m4a");
        verify(searchService, never()).deleteByAudioKeyQuietly("recordings/1/new.m4a");
    }

    @Test
    void segmentsOfCurrentAudioStay() {
        Recording recording = new Recording("Лекция");
        recording.attachAudio("recordings/1/a.m4a", "audio/mp4", 10);
        when(recordingRepository.findById(1L)).thenReturn(Optional.of(recording));
        when(audioStorageService.readText("recordings/1/a/transcription.json")).thenReturn("[]");

        reindexService.indexTranscription(1L, "recordings/1/a.m4a", "recordings/1/a/transcription.json");

        verify(searchService).replaceTranscription(1L, "recordings/1/a.m4a", "[]");
        verify(searchService, never()).deleteByAudioKeyQuietly("recordings/1/a.m4a");
    }
}
