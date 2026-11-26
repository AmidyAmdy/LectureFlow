package ru.filatov.lectureflow.search;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import ru.filatov.lectureflow.recording.Recording;
import ru.filatov.lectureflow.recording.RecordingRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX_SETTINGS_FILE = "opensearch/transcription-segments.json";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final RecordingRepository recordingRepository;
    private final String indexName;

    public SearchService(
            RestClient.Builder builder,
            @Value("${lectureflow.opensearch.url}") String url,
            @Value("${lectureflow.opensearch.index}") String indexName,
            JsonMapper jsonMapper,
            RecordingRepository recordingRepository) {
        this.restClient = builder.baseUrl(url).build();
        this.indexName = indexName;
        this.jsonMapper = jsonMapper;
        this.recordingRepository = recordingRepository;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getClusterHealth() {
        return restClient.get().uri("/_cluster/health").retrieve().body(String.class);
    }

    public boolean createIndexIfMissing() {
        if (indexExists()) {
            return false;
        }
        try {
            restClient.put().uri("/{index}", indexName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(readIndexSettings())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Создан индекс OpenSearch {}", indexName);
            return true;
        } catch (HttpClientErrorException.BadRequest e) {
            if (e.getResponseBodyAsString().contains("resource_already_exists_exception")) {
                return false;
            }
            throw e;
        }
    }

    public List<String> findMappingProblems() {
        String response = restClient.get().uri("/{index}/_mapping", indexName).retrieve().body(String.class);
        JsonNode properties = jsonMapper.readTree(response).path(indexName).path("mappings").path("properties");
        List<String> problems = new ArrayList<>();
        checkField(properties, "recordingId", "type", "long", problems);
        checkField(properties, "audioObjectKey", "type", "keyword", problems);
        checkField(properties, "start", "type", "double", problems);
        checkField(properties, "end", "type", "double", problems);
        checkField(properties, "text", "analyzer", "russian_text", problems);
        return problems;
    }

    public int replaceTranscription(Long recordingId, String audioObjectKey, String transcriptionJson) {
        TranscriptSegment[] segments = jsonMapper.readValue(transcriptionJson, TranscriptSegment[].class);
        deleteByAudioKey(audioObjectKey);
        if (segments.length == 0) {
            return 0;
        }

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            TranscriptSegment segment = segments[i];

            String documentId = audioObjectKey + ":" + i;
            Map<String, Object> action = Map.of("index", Map.of("_index", indexName, "_id", documentId));
            SearchDocument document = new SearchDocument(
                    recordingId, audioObjectKey, segment.start(), segment.end(), segment.text());
            body.append(jsonMapper.writeValueAsString(action)).append('\n');
            body.append(jsonMapper.writeValueAsString(document)).append('\n');
        }

        String response = restClient.post()
                .uri("/_bulk?refresh=wait_for")
                .contentType(NDJSON)
                .body(body.toString())
                .retrieve()
                .body(String.class);
        checkBulkResponse(response, segments.length);
        return segments.length;
    }

    public void deleteByRecordingIdQuietly(Long recordingId) {
        try {
            deleteByQuery(Map.of("term", Map.of("recordingId", recordingId)));
        } catch (RestClientException e) {
            log.warn("Не удалось удалить из поиска сегменты записи {}: {}. Их уберёт переиндексация.",
                    recordingId, e.getMessage());
        }
    }

    public void deleteByAudioKeyQuietly(String audioObjectKey) {
        try {
            deleteByAudioKey(audioObjectKey);
        } catch (RestClientException e) {
            log.warn("Не удалось удалить из поиска сегменты аудио {}: {}. Их уберёт переиндексация.",
                    audioObjectKey, e.getMessage());
        }
    }

    public void deleteOutdatedDocuments(List<String> currentAudioKeys) {
        Map<String, Object> query = Map.of("bool",
                Map.of("must_not", List.of(Map.of("terms", Map.of("audioObjectKey", currentAudioKeys)))));
        deleteByQuery(query);
    }

    public SearchPage search(String query, int page, int size) {
        List<String> currentAudioKeys = recordingRepository.findCurrentAudioObjectKeys();
        if (currentAudioKeys.isEmpty()) {
            return new SearchPage(List.of(), page, size, 0, 0);
        }

        Map<String, Object> match = Map.of("match",
                Map.of("text", Map.of("query", query, "operator", "and")));
        Map<String, Object> filter = Map.of("terms", Map.of("audioObjectKey", currentAudioKeys));
        Map<String, Object> request = Map.of(
                "from", page * size,
                "size", size,
                "track_total_hits", true,
                "query", Map.of("bool", Map.of("must", List.of(match), "filter", List.of(filter))),
                "sort", List.of("_score", Map.of("recordingId", "desc"), Map.of("start", "asc")));

        String response;
        try {
            response = restClient.post()
                    .uri("/{index}/_search", indexName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new SearchUnavailableException("Индекс поиска " + indexName
                    + " не найден. Перезапустите backend или выполните POST /search/reindex.", e);
        }

        JsonNode hits = jsonMapper.readTree(response).path("hits");
        long total = hits.path("total").path("value").asLong();

        List<SearchDocument> documents = new ArrayList<>();
        List<Long> recordingIds = new ArrayList<>();
        for (JsonNode hit : hits.path("hits")) {
            SearchDocument document = jsonMapper.treeToValue(hit.path("_source"), SearchDocument.class);
            documents.add(document);
            recordingIds.add(document.recordingId());
        }

        Map<Long, Recording> recordingsById = new HashMap<>();
        for (Recording recording : recordingRepository.findAllById(recordingIds)) {
            recordingsById.put(recording.getId(), recording);
        }

        List<SearchResult> results = new ArrayList<>();
        for (SearchDocument document : documents) {
            Recording recording = recordingsById.get(document.recordingId());

            if (recording == null || !Objects.equals(recording.getAudioObjectKey(), document.audioObjectKey())) {
                continue;
            }
            results.add(new SearchResult(
                    document.recordingId(),
                    recording.getTitle(),
                    document.start(),
                    document.end(),
                    document.text()));
        }
        int totalPages = (int) ((total + size - 1) / size);
        return new SearchPage(results, page, size, total, totalPages);
    }

    private boolean indexExists() {
        try {
            restClient.head().uri("/{index}", indexName).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    private void deleteByAudioKey(String audioObjectKey) {
        deleteByQuery(Map.of("term", Map.of("audioObjectKey", audioObjectKey)));
    }

    private void deleteByQuery(Map<String, Object> query) {
        try {

            restClient.post()
                    .uri("/{index}/_delete_by_query?refresh=true&conflicts=proceed", indexName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound ignored) {
        }
    }

    private void checkBulkResponse(String response, int documentCount) {
        JsonNode root = jsonMapper.readTree(response);
        if (!root.path("errors").asBoolean()) {
            return;
        }
        int failed = 0;
        String firstError = null;
        for (JsonNode item : root.path("items")) {
            JsonNode error = item.path("index").path("error");
            if (!error.isMissingNode()) {
                failed++;
                if (firstError == null) {
                    firstError = error.path("type").asString("") + ": " + error.path("reason").asString("");
                }
            }
        }
        throw new SearchIndexingException("OpenSearch не сохранил " + failed + " из " + documentCount
                + " сегментов. Первая ошибка — " + firstError);
    }

    private void checkField(JsonNode properties, String field, String attribute, String expected,
            List<String> problems) {
        String actual = properties.path(field).path(attribute).asString("");
        if (!expected.equals(actual)) {
            problems.add(field + "." + attribute + " = '" + actual + "', ожидается '" + expected + "'");
        }
    }

    private String readIndexSettings() {
        try (InputStream inputStream = new ClassPathResource(INDEX_SETTINGS_FILE).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не найден файл " + INDEX_SETTINGS_FILE, e);
        }
    }
}
