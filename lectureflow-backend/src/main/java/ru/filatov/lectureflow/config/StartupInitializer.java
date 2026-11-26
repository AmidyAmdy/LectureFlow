package ru.filatov.lectureflow.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import ru.filatov.lectureflow.search.ReindexService;
import ru.filatov.lectureflow.search.SearchService;
import ru.filatov.lectureflow.storage.AudioStorageService;

@Component
public class StartupInitializer {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final AudioStorageService audioStorageService;
    private final SearchService searchService;
    private final ReindexService reindexService;

    public StartupInitializer(AudioStorageService audioStorageService, SearchService searchService,
            ReindexService reindexService) {
        this.audioStorageService = audioStorageService;
        this.searchService = searchService;
        this.reindexService = reindexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareExternalServices() {
        prepareBucket();
        prepareSearchIndex();
    }

    private void prepareBucket() {
        try {
            if (audioStorageService.createBucketIfMissing()) {
                log.info("RustFS: создан бакет {}", audioStorageService.getBucket());
            } else {
                log.info("RustFS: бакет {} доступен", audioStorageService.getBucket());
            }
        } catch (RuntimeException e) {
            log.error("RustFS недоступен ({}). Загрузка и выдача аудио не будут работать, "
                    + "пока он не запустится: проверьте docker compose ps и LECTUREFLOW_S3_*.", e.getMessage());
        }
    }

    private void prepareSearchIndex() {
        String index = searchService.getIndexName();
        try {
            if (searchService.createIndexIfMissing()) {
                log.info("OpenSearch: индекс {} создан, наполняю его из PostgreSQL и RustFS", index);
                reindexService.reindexAll();
                return;
            }
            List<String> problems = searchService.findMappingProblems();
            if (problems.isEmpty()) {
                log.info("OpenSearch: индекс {} готов", index);
            } else {
                log.warn("OpenSearch: mapping индекса {} не совпадает с ожидаемым: {}. Задайте новое имя в "
                        + "LECTUREFLOW_OPENSEARCH_INDEX и выполните POST /search/reindex.", index, problems);
            }
        } catch (RuntimeException e) {
            log.error("OpenSearch недоступен ({}). Поиск не будет работать; после запуска OpenSearch "
                    + "выполните POST /search/reindex.", e.getMessage());
        }
    }
}
