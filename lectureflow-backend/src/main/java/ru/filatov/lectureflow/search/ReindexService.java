package ru.filatov.lectureflow.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingRepository;
import ru.filatov.lectureflow.storage.AudioStorageService;

@Service
public class ReindexService {

    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);

    private final RecordingRepository recordingRepository;
    private final AudioStorageService audioStorageService;
    private final SearchService searchService;

    public ReindexService(RecordingRepository recordingRepository, AudioStorageService audioStorageService,
            SearchService searchService) {
        this.recordingRepository = recordingRepository;
        this.audioStorageService = audioStorageService;
        this.searchService = searchService;
    }

    public void indexTranscription(Long recordingId, String audioObjectKey, String transcriptionObjectKey) {
        if (searchService.createIndexIfMissing()) {
            log.warn("Индекс {} отсутствовал и создан заново — выполняю полную переиндексацию",
                    searchService.getIndexName());
            reindexAll();
            return;
        }
        String transcriptionJson = audioStorageService.readText(transcriptionObjectKey);
        int segments = searchService.replaceTranscription(recordingId, audioObjectKey, transcriptionJson);

        if (!isCurrentAudio(recordingId, audioObjectKey)) {
            searchService.deleteByAudioKeyQuietly(audioObjectKey);
            log.info("Запись {}: аудио {} заменено во время индексации, его сегменты удалены из поиска",
                    recordingId, audioObjectKey);
            return;
        }
        log.info("Запись {}: в поиск добавлено сегментов: {}", recordingId, segments);
    }

    public ReindexResult reindexAll() {
        searchService.createIndexIfMissing();
        List<Recording> recordings = recordingRepository.findByTranscriptionObjectKeyIsNotNullOrderByIdAsc();
        int indexedRecordings = 0;
        int indexedSegments = 0;
        List<String> failures = new ArrayList<>();

        for (Recording recording : recordings) {
            try {
                String transcriptionJson = audioStorageService.readText(recording.getTranscriptionObjectKey());
                indexedSegments += searchService.replaceTranscription(
                        recording.getId(), recording.getAudioObjectKey(), transcriptionJson);
                indexedRecordings++;
            } catch (RuntimeException e) {
                log.error("Переиндексация записи {} не удалась: {}", recording.getId(), e.getMessage());
                failures.add("Запись " + recording.getId() + ": " + e.getMessage());
            }
        }

        searchService.deleteOutdatedDocuments(recordingRepository.findCurrentAudioObjectKeys());

        log.info("Переиндексация завершена: записей {}, проиндексировано {}, сегментов {}, ошибок {}",
                recordings.size(), indexedRecordings, indexedSegments, failures.size());
        return new ReindexResult(recordings.size(), indexedRecordings, indexedSegments, failures);
    }

    private boolean isCurrentAudio(Long recordingId, String audioObjectKey) {
        Recording recording = recordingRepository.findById(recordingId).orElse(null);
        return recording != null && Objects.equals(recording.getAudioObjectKey(), audioObjectKey);
    }
}
