package ru.filatov.lectureflow.search;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("search")
public class SearchHealthIndicator implements HealthIndicator {

    private final SearchService searchService;

    public SearchHealthIndicator(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public Health health() {
        try {
            searchService.getClusterHealth();
            return Health.up().withDetail("index", searchService.getIndexName()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("index", searchService.getIndexName())
                    .withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }
}
