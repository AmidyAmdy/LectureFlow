package ru.filatov.lectureflow.recording;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.filatov.lectureflow.recording.web.RecordingPage;
import ru.filatov.lectureflow.recording.web.RecordingResponse;
import ru.filatov.lectureflow.search.SearchService;
import ru.filatov.lectureflow.storage.AudioFile;
import ru.filatov.lectureflow.storage.AudioFormat;
import ru.filatov.lectureflow.storage.AudioStorageService;
import ru.filatov.lectureflow.storage.StorageUnavailableException;

import software.amazon.awssdk.core.exception.SdkException;

@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    private final RecordingRepository recordingRepository;
    private final AudioStorageService audioStorageService;
    private final SearchService searchService;

    public RecordingService(RecordingRepository recordingRepository, AudioStorageService audioStorageService,
            SearchService searchService) {
        this.recordingRepository = recordingRepository;
        this.audioStorageService = audioStorageService;
        this.searchService = searchService;
    }

    public RecordingResponse getRecording(Long id) {
        return RecordingResponse.from(findRecording(id));
    }

    public List<RecordingResponse> getRecordings() {
        ArrayList<RecordingResponse> responses = new ArrayList<>();
        List<Recording> recordings = recordingRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        for (Recording recording : recordings) {
            responses.add(RecordingResponse.from(recording));
        }
        return responses;
    }

    public RecordingPage getRecordingPage(int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = Sort.Direction.fromString(direction);

        Sort order = Sort.by(sortDirection, sort).and(Sort.by(sortDirection, "id"));
        Page<Recording> result = recordingRepository.findAll(PageRequest.of(page, size, order));

        List<RecordingResponse> items = new ArrayList<>();
        for (Recording recording : result.getContent()) {
            items.add(RecordingResponse.from(recording));
        }
        return new RecordingPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    public RecordingResponse createRecording(String title) {
        Recording recordingEntity = new Recording(title.trim());
        recordingEntity = recordingRepository.save(recordingEntity);
        return RecordingResponse.from(recordingEntity);
    }

    @Transactional
    public RecordingResponse renameRecording(Long id, String newTitle) {
        Recording recordingEntity = findRecording(id);
        recordingEntity.rename(newTitle.trim());
        return RecordingResponse.from(recordingEntity);
    }

    public void deleteRecording(Long id) {
        Recording recordingEntity = findRecording(id);
        for (String key : objectKeysOf(recordingEntity)) {
            try {
                audioStorageService.delete(key);
            } catch (SdkException e) {
                throw new StorageUnavailableException(
                        "Не удалось удалить файлы записи из RustFS. Запись не удалена — повторите удаление позже.", e);
            }
        }
        recordingRepository.delete(recordingEntity);
        searchService.deleteByRecordingIdQuietly(id);
        log.info("Запись {} удалена", id);
    }

    public String getTranscription(Long id) {
        Recording recordingEntity = findRecording(id);
        if (recordingEntity.getTranscriptionObjectKey() == null) {
            throw new TranscriptionNotReadyException(id);
        }
        return audioStorageService.readText(recordingEntity.getTranscriptionObjectKey());
    }

    public String getSummary(Long id) {
        Recording recordingEntity = findRecording(id);
        if (recordingEntity.getSummaryObjectKey() == null) {
            throw new SummaryNotReadyException(id);
        }
        return audioStorageService.readText(recordingEntity.getSummaryObjectKey());
    }

    public AudioFile getAudioFile(Long id) {
        Recording recordingEntity = findRecording(id);
        String key = recordingEntity.getAudioObjectKey();
        if (key == null) {
            throw new AudioNotFoundException(id);
        }
        if (recordingEntity.getAudioContentType() != null && recordingEntity.getAudioSize() != null) {
            return new AudioFile(key, recordingEntity.getAudioContentType(), recordingEntity.getAudioSize());
        }

        long size = audioStorageService.getObjectSize(key);
        AudioFormat format = AudioFormat.detect(audioStorageService.readHeader(key, AudioFormat.HEADER_SIZE));
        String contentType = "application/octet-stream";
        if (format != null) {
            contentType = format.contentType();
        }
        return new AudioFile(key, contentType, size);
    }

    private Recording findRecording(Long id) {
        return recordingRepository.findById(id)
                .orElseThrow(() -> new RecordingNotFoundException(id));
    }

    private Set<String> objectKeysOf(Recording recording) {
        Set<String> keys = new LinkedHashSet<>();
        if (recording.getAudioObjectKey() != null) {
            keys.addAll(AudioStorageService.objectKeysOfAudio(recording.getAudioObjectKey()));
        }
        if (recording.getTranscriptionObjectKey() != null) {
            keys.add(recording.getTranscriptionObjectKey());
        }
        if (recording.getSummaryObjectKey() != null) {
            keys.add(recording.getSummaryObjectKey());
        }
        return keys;
    }
}
