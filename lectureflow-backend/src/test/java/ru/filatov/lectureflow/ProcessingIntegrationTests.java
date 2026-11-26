package ru.filatov.lectureflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import ru.filatov.lectureflow.processing.ProcessingService;
import ru.filatov.lectureflow.processing.messaging.RabbitConfig;
import ru.filatov.lectureflow.processing.messaging.SummaryCompleted;
import ru.filatov.lectureflow.processing.messaging.TranscriptionCompleted;
import ru.filatov.lectureflow.processing.messaging.TranscriptionFailed;
import ru.filatov.lectureflow.processing.messaging.TranscriptionPublisher;
import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingRepository;
import ru.filatov.lectureflow.recording.RecordingStatus;
import ru.filatov.lectureflow.search.ReindexService;
import ru.filatov.lectureflow.search.SearchService;
import ru.filatov.lectureflow.storage.AudioStorageService;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@SpringBootTest(properties = {
        "lectureflow.s3.access-key=test",
        "lectureflow.s3.secret-key=test",
        "lectureflow.s3.endpoint=http://127.0.0.1:9",
        "lectureflow.opensearch.url=http://127.0.0.1:9",
        "spring.datasource.password=test",
        "spring.rabbitmq.password=test",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProcessingIntegrationTests {

    private static final byte[] M4A = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private ProcessingService processingService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @MockitoBean
    private AudioStorageService audioStorageService;

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private ReindexService reindexService;

    @MockitoSpyBean
    private TranscriptionPublisher transcriptionPublisher;

    @BeforeEach
    void cleanUp() {
        recordingRepository.deleteAll();
        amqpAdmin.purgeQueue(RabbitConfig.TRANSCRIPTION_QUEUE, false);
        amqpAdmin.purgeQueue(RabbitConfig.BACKEND_FAILED_QUEUE, false);
    }

    @Test
    void createRecordingValidatesTitle() throws Exception {
        mockMvc.perform(post("/recordings").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"  Лекция 1  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Лекция 1"))
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(post("/recordings").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("title: Введите название записи"));

        String longTitle = "a".repeat(256);
        mockMvc.perform(post("/recordings").contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"" + longTitle + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/recordings/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));

        mockMvc.perform(get("/recordings/page").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pageIsSortedAndOldListStillWorks() throws Exception {
        recordingRepository.save(new Recording("Б"));
        recordingRepository.save(new Recording("А"));
        recordingRepository.save(new Recording("В"));

        mockMvc.perform(get("/recordings/page").param("size", "2").param("sort", "title").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("А"))
                .andExpect(jsonPath("$.items[1].title").value("Б"))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/recordings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void uploadDetectsFormatByContentAndQueuesTask() throws Exception {
        Long id = recordingRepository.save(new Recording("Лекция")).getId();
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd.mp3", "audio/mpeg", M4A);

        mockMvc.perform(multipart("/recordings/{id}/audio", id).file(file))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(audioStorageService).upload(key.capture(), any(MultipartFile.class), eq("audio/mp4"));

        assertThat(key.getValue()).matches("recordings/" + id + "/[0-9a-f-]{36}\\.m4a");

        Recording recording = recordingRepository.findById(id).orElseThrow();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.UPLOADED);
        assertThat(recording.getAudioContentType()).isEqualTo("audio/mp4");
        assertThat(recording.getAudioSize()).isEqualTo(M4A.length);

        Object task = rabbitTemplate.receiveAndConvert(RabbitConfig.TRANSCRIPTION_QUEUE, 5000);
        assertThat(task).isEqualTo("{\"recordingId\":" + id + ",\"audioObjectKey\":\"" + key.getValue() + "\"}");
    }

    @Test
    void emptyAndUnknownFilesAreRejected() throws Exception {
        Long id = recordingRepository.save(new Recording("Лекция")).getId();

        mockMvc.perform(multipart("/recordings/{id}/audio", id).file(new MockMultipartFile("file", "a.m4a", "audio/mp4", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));
        mockMvc.perform(multipart("/recordings/{id}/audio", id)
                        .file(new MockMultipartFile("file", "a.m4a", "audio/mp4", "просто текст".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIO"));
        mockMvc.perform(multipart("/recordings/{id}/audio", id))
                .andExpect(status().isBadRequest());

        verify(audioStorageService, never()).upload(anyString(), any(), anyString());
        assertThat(recordingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.NEW);
    }

    @Test
    void failedPublishMarksRecordingAndRetryQueuesAgain() throws Exception {
        Long id = recordingRepository.save(new Recording("Лекция")).getId();
        doThrow(new AmqpException("RabbitMQ недоступен")).when(transcriptionPublisher).publish(any());

        mockMvc.perform(multipart("/recordings/{id}/audio", id).file(new MockMultipartFile("file", "a.m4a", "audio/mp4", M4A)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUEUE_UNAVAILABLE"));

        mockMvc.perform(get("/recordings/{id}", id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureStage").value("queue"));

        doCallRealMethod().when(transcriptionPublisher).publish(any());
        mockMvc.perform(post("/recordings/{id}/retry", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.failureStage").doesNotExist());
        assertThat(rabbitTemplate.receiveAndConvert(RabbitConfig.TRANSCRIPTION_QUEUE, 5000)).isNotNull();
    }

    @Test
    void staleDuplicateAndReorderedEventsDoNotBreakState() {
        Long id = recordingRepository.save(new Recording("Лекция")).getId();
        String oldKey = "recordings/" + id + "/old.m4a";
        String newKey = "recordings/" + id + "/new.m4a";
        Recording withAudio = recordingRepository.findById(id).orElseThrow();
        withAudio.attachAudio(newKey, "audio/mp4", 100);
        recordingRepository.save(withAudio);

        assertThat(processingService.completeTranscription(
                new TranscriptionCompleted(id, oldKey, "recordings/" + id + "/old/transcription.json"))).isFalse();
        verify(audioStorageService).deleteQuietly("recordings/" + id + "/old/transcription.json");
        verify(audioStorageService).deleteQuietly("recordings/" + id + "/old/summaries.json");
        verify(audioStorageService, never()).deleteQuietly(newKey);

        String newTranscription = "recordings/" + id + "/new/transcription.json";
        String newSummary = "recordings/" + id + "/new/summaries.json";
        processingService.completeSummary(new SummaryCompleted(id, newSummary, newKey));
        assertThat(processingService.completeTranscription(new TranscriptionCompleted(id, newKey, newTranscription))).isTrue();
        processingService.completeTranscription(new TranscriptionCompleted(id, newKey, newTranscription));
        processingService.completeSummary(new SummaryCompleted(id, newSummary, newKey));
        processingService.failProcessing(new TranscriptionFailed(id, newKey, "summarize", "поздняя ошибка"));

        Recording recording = recordingRepository.findById(id).orElseThrow();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
        assertThat(recording.getTranscriptionObjectKey()).isEqualTo(newTranscription);
        assertThat(recording.getSummaryObjectKey()).isEqualTo(newSummary);

        assertThat(processingService.completeTranscription(new TranscriptionCompleted(987654L, newKey, "x.json"))).isFalse();

        verify(audioStorageService, never()).deleteQuietly("recordings/" + id + "/new/transcription.json");
    }

    @Test
    void workerFailureKeepsTranscriptionAndAllowsRetry() throws Exception {
        String key = "recordings/1/a.m4a";
        Long id = recordingWithAudio(key).getId();
        processingService.completeTranscription(new TranscriptionCompleted(id, key, "recordings/1/a/transcription.json"));
        processingService.failProcessing(new TranscriptionFailed(id, key, "summarize", "Ollama недоступна"));
        when(audioStorageService.readText("recordings/1/a/transcription.json")).thenReturn("[{\"start\":0,\"end\":1,\"text\":\"Привет\"}]");

        mockMvc.perform(get("/recordings/{id}", id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureStage").value("summarize"))
                .andExpect(jsonPath("$.failureMessage").value("Ollama недоступна"))
                .andExpect(jsonPath("$.transcriptionReady").value(true));
        mockMvc.perform(get("/recordings/{id}/transcription", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("Привет"));
        mockMvc.perform(get("/recordings/{id}/summary", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUMMARY_NOT_READY"));

        mockMvc.perform(post("/recordings/{id}/retry", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("TRANSCRIBED"));
        Object task = rabbitTemplate.receiveAndConvert(RabbitConfig.TRANSCRIPTION_QUEUE, 5000);
        assertThat(task).isEqualTo("{\"recordingId\":" + id + ",\"audioObjectKey\":\"" + key + "\"}");
    }

    @Test
    void listenersHandleRealMessagesAndMoveInvalidOnesToBackendFailedQueue() {
        String key = "recordings/2/a.m4a";
        Long id = recordingWithAudio(key).getId();

        rabbitTemplate.convertAndSend("", RabbitConfig.TRANSCRIPTION_COMPLETED_QUEUE,
                "{\"recordingId\":" + id + ",\"audioObjectKey\":\"" + key + "\",\"transcriptionObjectKey\":\"recordings/2/a/transcription.json\"}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(recordingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.TRANSCRIBED));
        verify(reindexService).indexTranscription(id, key, "recordings/2/a/transcription.json");

        rabbitTemplate.convertAndSend("", RabbitConfig.TRANSCRIPTION_FAILED_QUEUE,
                "{\"recordingId\":" + id + ",\"audioObjectKey\":\"" + key + "\",\"stage\":\"summarize\","
                        + "\"error\":\"RuntimeError: модель не ответила\",\"permanent\":false,\"attempts\":3,"
                        + "\"maxAttempts\":3,\"failedAt\":\"2026-11-05T21:10:47+00:00\",\"originalMessage\":\"{}\"}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(recordingRepository.findById(id).orElseThrow().getFailureStage()).isEqualTo("summarize"));

        rabbitTemplate.convertAndSend("", RabbitConfig.TRANSCRIPTION_COMPLETED_QUEUE, "это не JSON");
        rabbitTemplate.convertAndSend("", RabbitConfig.SUMMARY_COMPLETED_QUEUE, "");
        rabbitTemplate.convertAndSend("", RabbitConfig.TRANSCRIPTION_FAILED_QUEUE,
                "{\"recordingId\":null,\"audioObjectKey\":null,\"stage\":\"validate\",\"error\":\"Невалидный JSON\"}");

        for (int i = 0; i < 3; i++) {
            Message failed = rabbitTemplate.receive(RabbitConfig.BACKEND_FAILED_QUEUE, 15000);
            assertThat(failed).isNotNull();
            assertThat(failed.getMessageProperties().getHeaders()).containsKey("x-exception-message");
        }
        assertThat(amqpAdmin.getQueueInfo(RabbitConfig.TRANSCRIPTION_COMPLETED_QUEUE).getMessageCount()).isZero();
        assertThat(amqpAdmin.getQueueInfo(RabbitConfig.SUMMARY_COMPLETED_QUEUE).getMessageCount()).isZero();
        assertThat(amqpAdmin.getQueueInfo(RabbitConfig.TRANSCRIPTION_FAILED_QUEUE).getMessageCount()).isZero();
    }

    @Test
    void versionDetectsConcurrentUpdate() {
        String key = "recordings/3/a.m4a";
        Long id = recordingWithAudio(key).getId();
        Recording staleCopy = recordingRepository.findById(id).orElseThrow();

        processingService.completeSummary(new SummaryCompleted(id, "recordings/3/a/summaries.json", key));

        staleCopy.rename("Переименование по устаревшей версии");
        assertThatThrownBy(() -> recordingRepository.save(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        processingService.completeTranscription(new TranscriptionCompleted(id, key, "recordings/3/a/transcription.json"));
        Recording recording = recordingRepository.findById(id).orElseThrow();
        assertThat(recording.getStatus()).isEqualTo(RecordingStatus.SUMMARIZED);
        assertThat(recording.getTitle()).isEqualTo("Лекция");
    }

    @Test
    void audioSupportsRangeRequests() throws Exception {
        Long id = recordingWithAudio("recordings/4/a.m4a").getId();
        byte[] content = new byte[100];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        when(audioStorageService.openRange("recordings/4/a.m4a", 10, 19)).thenReturn(stream(Arrays.copyOfRange(content, 10, 20)));
        when(audioStorageService.openRange("recordings/4/a.m4a", 0, 99)).thenReturn(stream(content));

        mockMvc.perform(get("/recordings/{id}/audio", id).header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().longValue("Content-Length", 10))
                .andExpect(content().contentType("audio/mp4"))
                .andExpect(content().bytes(Arrays.copyOfRange(content, 10, 20)));

        mockMvc.perform(get("/recordings/{id}/audio", id))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 100))
                .andExpect(content().bytes(content));

        mockMvc.perform(get("/recordings/{id}/audio", id).header("Range", "bytes=500-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */100"))
                .andExpect(jsonPath("$.code").value("RANGE_NOT_SATISFIABLE"));

        Long withoutAudio = recordingRepository.save(new Recording("Без аудио")).getId();
        mockMvc.perform(get("/recordings/{id}/audio", withoutAudio))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIO_NOT_FOUND"));
    }

    @Test
    void deleteRemovesKnownObjectsSearchDocumentsAndRow() throws Exception {
        String key = "recordings/5/a.m4a";
        Long id = recordingWithAudio(key).getId();
        processingService.completeTranscription(new TranscriptionCompleted(id, key, "recordings/5/a/transcription.json"));

        mockMvc.perform(delete("/recordings/{id}", id)).andExpect(status().isNoContent());

        verify(audioStorageService).delete("recordings/5/a.m4a");
        verify(audioStorageService).delete("recordings/5/a/transcription.json");
        verify(audioStorageService).delete("recordings/5/a/summaries.json");
        verify(audioStorageService, times(3)).delete(anyString());
        verify(searchService).deleteByRecordingIdQuietly(id);
        assertThat(recordingRepository.findById(id)).isEmpty();
    }

    @Test
    void failedFileDeletionKeepsRecordingForRetry() throws Exception {
        Long id = recordingWithAudio("recordings/6/a.m4a").getId();
        doThrow(SdkClientException.create("RustFS недоступен")).when(audioStorageService).delete(anyString());

        mockMvc.perform(delete("/recordings/{id}", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        assertThat(recordingRepository.findById(id)).isPresent();
        verify(searchService, never()).deleteByRecordingIdQuietly(id);
    }

    @Test
    void searchValidatesQuery() throws Exception {
        mockMvc.perform(get("/search/segments").param("q", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Введите текст для поиска"));
        mockMvc.perform(get("/search/segments").param("q", "x").param("size", "51"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/search/segments").param("q", "x").param("page", "600"))
                .andExpect(status().isBadRequest());
    }

    private Recording recordingWithAudio(String key) {
        Recording recording = new Recording("Лекция");
        recording.attachAudio(key, "audio/mp4", 100);
        return recordingRepository.save(recording);
    }

    private static ResponseInputStream<GetObjectResponse> stream(byte[] bytes) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }
}
