package ru.filatov.lectureflow.processing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ru.filatov.lectureflow.processing.messaging.RabbitConfig;
import ru.filatov.lectureflow.processing.messaging.SummaryCompleted;
import ru.filatov.lectureflow.processing.messaging.TaskNotQueuedException;
import ru.filatov.lectureflow.processing.messaging.TranscriptionCompleted;
import ru.filatov.lectureflow.processing.messaging.TranscriptionFailed;
import ru.filatov.lectureflow.processing.messaging.TranscriptionPublisher;
import ru.filatov.lectureflow.processing.messaging.TranscriptionTask;
import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingNotFoundException;
import ru.filatov.lectureflow.recording.RecordingRepository;
import ru.filatov.lectureflow.recording.web.RecordingResponse;
import ru.filatov.lectureflow.search.SearchService;
import ru.filatov.lectureflow.storage.AudioFormat;
import ru.filatov.lectureflow.storage.AudioStorageService;

@Service
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);

    private final RecordingRepository recordingRepository;
    private final AudioStorageService audioStorageService;
    private final TranscriptionPublisher transcriptionPublisher;
    private final SearchService searchService;

    public ProcessingService(RecordingRepository recordingRepository, AudioStorageService audioStorageService,
            TranscriptionPublisher transcriptionPublisher, SearchService searchService) {
        this.recordingRepository = recordingRepository;
        this.audioStorageService = audioStorageService;
        this.transcriptionPublisher = transcriptionPublisher;
        this.searchService = searchService;
    }

    public void attachAudio(Long id, MultipartFile file) throws IOException {
        Recording recording = findRecording(id);
        AudioFormat format = detectFormat(file);

        String key = "recordings/" + id + "/" + UUID.randomUUID() + "." + format.extension();
        audioStorageService.upload(key, file, format.contentType());

        String oldAudioKey = recording.getAudioObjectKey();
        recording.attachAudio(key, format.contentType(), file.getSize());
        try {
            recording = recordingRepository.save(recording);
        } catch (RuntimeException e) {
            audioStorageService.deleteQuietly(key);
            throw e;
        }
        log.info("Запись {}: загружено аудио {} ({}, {} байт)", id, key, format.contentType(), file.getSize());

        if (oldAudioKey != null) {
            removeOldUpload(id, oldAudioKey);
        }
        publishTask(recording);
    }

    public RecordingResponse restartProcessing(Long id) {
        Recording recording = findRecording(id);
        if (!recording.canRestartProcessing()) {
            if (recording.getAudioObjectKey() == null) {
                throw new ProcessingNotAllowedException("Сначала загрузите аудио");
            }
            throw new ProcessingNotAllowedException("Обработка уже завершена, повторный запуск не нужен");
        }
        recording.restartProcessing();
        recording = recordingRepository.save(recording);
        log.info("Запись {}: повторный запуск обработки {}", id, recording.getAudioObjectKey());
        publishTask(recording);
        return RecordingResponse.from(recording);
    }

    @Transactional
    public boolean completeTranscription(TranscriptionCompleted event) {
        Recording recording = findCurrentRecording(event.recordingId(), event.audioObjectKey(), "транскрипция");
        if (recording == null) {
            return false;
        }
        recording.completeTranscription(event.transcriptionObjectKey());
        return true;
    }

    @Transactional
    public void completeSummary(SummaryCompleted event) {
        Recording recording = findCurrentRecording(event.recordingId(), event.audioObjectKey(), "конспекты");
        if (recording == null) {
            return;
        }
        recording.completeSummary(event.summaryObjectKey());
    }

    @Transactional
    public void failProcessing(TranscriptionFailed event) {
        Recording recording = findCurrentRecording(event.recordingId(), event.audioObjectKey(), "ошибка worker");
        if (recording == null) {
            return;
        }
        String stage = event.stage();
        if (stage == null || stage.isBlank()) {
            stage = "unknown";
        }
        String message = event.error();
        if (message == null || message.isBlank()) {
            message = "Worker не сообщил причину ошибки";
        }
        if (recording.failProcessing(stage, message)) {
            log.warn("Запись {}: worker сообщил об ошибке на этапе {}: {}", recording.getId(), stage, message);
        } else {
            log.info("Запись {}: ошибка worker на этапе {} уже неактуальна (статус {})",
                    recording.getId(), stage, recording.getStatus());
        }
    }

    private AudioFormat detectFormat(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new InvalidAudioException("Файл пустой. Выберите аудиофайл с записью лекции.");
        }
        byte[] header;
        try (InputStream inputStream = file.getInputStream()) {
            header = inputStream.readNBytes(AudioFormat.HEADER_SIZE);
        }
        AudioFormat format = AudioFormat.detect(header);
        if (format == null) {
            throw new InvalidAudioException(
                    "Не удалось распознать аудиофайл. Поддерживаются M4A/MP4, MP3, WAV, OGG/Opus, FLAC, WebM и AAC.");
        }
        return format;
    }

    private void publishTask(Recording recording) {
        Long id = recording.getId();
        String audioKey = recording.getAudioObjectKey();
        try {
            transcriptionPublisher.publish(new TranscriptionTask(id, audioKey));
            log.info("Запись {}: задание отправлено в очередь {}", id, RabbitConfig.TRANSCRIPTION_QUEUE);
        } catch (RuntimeException e) {
            log.error("Запись {}: задание не отправлено в RabbitMQ: {}", id, e.getMessage());
            markNotQueued(id, audioKey);
            throw new TaskNotQueuedException(id, e);
        }
    }

    private void markNotQueued(Long id, String audioKey) {
        try {
            Recording recording = findRecording(id);
            if (Objects.equals(recording.getAudioObjectKey(), audioKey)) {
                recording.failToQueue("Задание не удалось отправить в RabbitMQ. Проверьте, что RabbitMQ запущен, и повторите обработку.");
                recordingRepository.save(recording);
            }
        } catch (RuntimeException e) {
            log.error("Запись {}: не удалось сохранить ошибку отправки задания: {}", id, e.getMessage());
        }
    }

    private void removeOldUpload(Long id, String oldAudioKey) {
        for (String key : AudioStorageService.objectKeysOfAudio(oldAudioKey)) {
            audioStorageService.deleteQuietly(key);
        }
        searchService.deleteByAudioKeyQuietly(oldAudioKey);
        log.info("Запись {}: удалены файлы прежнего аудио {}", id, oldAudioKey);
    }

    private Recording findRecording(Long id) {
        return recordingRepository.findById(id)
                .orElseThrow(() -> new RecordingNotFoundException(id));
    }

    private Recording findCurrentRecording(Long recordingId, String audioObjectKey, String eventName) {
        Recording recording = recordingRepository.findById(recordingId).orElse(null);
        if (recording == null) {
            log.info("Событие «{}» для удалённой записи {} пропущено", eventName, recordingId);
            removeLateResults(recordingId, audioObjectKey);
            return null;
        }
        if (!Objects.equals(recording.getAudioObjectKey(), audioObjectKey)) {
            log.info("Событие «{}» для записи {} относится к прежнему аудио {} и пропущено",
                    eventName, recordingId, audioObjectKey);
            removeLateResults(recordingId, audioObjectKey);
            return null;
        }
        return recording;
    }

    private void removeLateResults(Long recordingId, String staleAudioKey) {
        if (staleAudioKey == null || !staleAudioKey.startsWith("recordings/" + recordingId + "/")) {
            return;
        }
        for (String key : AudioStorageService.objectKeysOfAudio(staleAudioKey)) {
            audioStorageService.deleteQuietly(key);
        }
    }
}
