package ru.filatov.lectureflow.recording.web;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.filatov.lectureflow.processing.ProcessingService;
import ru.filatov.lectureflow.recording.RecordingService;
import ru.filatov.lectureflow.storage.AudioFile;
import ru.filatov.lectureflow.storage.AudioStorageService;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@RestController
public class RecordingController {
    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);

    private final RecordingService recordingService;
    private final ProcessingService processingService;
    private final AudioStorageService audioStorageService;

    public RecordingController(RecordingService recordingService, ProcessingService processingService,
            AudioStorageService audioStorageService) {
        this.recordingService = recordingService;
        this.processingService = processingService;
        this.audioStorageService = audioStorageService;
    }

    @GetMapping("/recordings/{id}")
    public RecordingResponse getRecording(@PathVariable("id") @Positive(message = "id должен быть положительным") Long id) {
        return recordingService.getRecording(id);
    }

    @GetMapping("/recordings")
    public List<RecordingResponse> getRecordings() {
        return recordingService.getRecordings();
    }

    @GetMapping("/recordings/page")
    public RecordingPage getRecordingPage(
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "page не может быть отрицательным") int page,
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "size должен быть от 1 до 100")
            @Max(value = 100, message = "size должен быть от 1 до 100") int size,
            @RequestParam(value = "sort", defaultValue = "createdAt")
            @Pattern(regexp = "createdAt|title", message = "sort может быть createdAt или title") String sort,
            @RequestParam(value = "direction", defaultValue = "desc")
            @Pattern(regexp = "asc|desc", message = "direction может быть asc или desc") String direction) {
        return recordingService.getRecordingPage(page, size, sort, direction);
    }

    @PostMapping("/recordings")
    public ResponseEntity<RecordingResponse> createRecording(
            @Valid @RequestBody CreateRecordingRequest request) {
        RecordingResponse response = recordingService.createRecording(request.title());
        URI location = URI.create("/recordings/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PatchMapping("/recordings/{id}")
    public RecordingResponse renameRecording(
            @PathVariable("id") @Positive(message = "id должен быть положительным") Long id,
            @Valid @RequestBody RenameRecordingRequest request) {
        return recordingService.renameRecording(id, request.title());
    }

    @DeleteMapping("/recordings/{id}")
    public ResponseEntity<Void> deleteRecording(@PathVariable("id") @Positive(message = "id должен быть положительным") Long id) {
        recordingService.deleteRecording(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping ("/recordings/{id}/audio")
    public ResponseEntity<Void> uploadAudio(
            @PathVariable("id") @Positive(message = "id должен быть положительным") Long id,
            @RequestParam("file") MultipartFile file) throws IOException {
        processingService.attachAudio(id, file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/recordings/{id}/audio")
    public void getAudio(
            @PathVariable("id") @Positive(message = "id должен быть положительным") Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletResponse response) throws IOException {
        AudioFile audio = recordingService.getAudioFile(id);
        ByteRange range = ByteRange.parse(rangeHeader, audio.size());

        long start = 0;
        long end = audio.size() - 1;
        if (range != null) {
            start = range.start();
            end = range.end();
        }

        try (ResponseInputStream<GetObjectResponse> audioStream = audioStorageService.openRange(audio.objectKey(), start, end)) {
            if (range != null) {
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + audio.size());
            } else {
                response.setStatus(HttpStatus.OK.value());
            }
            response.setContentType(audio.contentType());
            response.setContentLengthLong(end - start + 1);
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            try {
                audioStream.transferTo(response.getOutputStream());
            } catch (IOException e) {

                audioStream.abort();
                log.debug("Передача аудио записи {} прервана: {}", id, e.getMessage());
            }
        }
    }

    @PostMapping("/recordings/{id}/retry")
    public ResponseEntity<RecordingResponse> retryProcessing(
            @PathVariable("id") @Positive(message = "id должен быть положительным") Long id) {
        return ResponseEntity.accepted().body(processingService.restartProcessing(id));
    }

    @GetMapping (value ="/recordings/{id}/transcription", produces = "application/json")
    public String getTranscription(@PathVariable("id") @Positive(message = "id должен быть положительным") Long id) {
        return recordingService.getTranscription(id);
    }

    @GetMapping (value = "/recordings/{id}/summary", produces = "application/json")
    public String getSummary(@PathVariable("id") @Positive(message = "id должен быть положительным") Long id) {
        return recordingService.getSummary(id);
    }
}
