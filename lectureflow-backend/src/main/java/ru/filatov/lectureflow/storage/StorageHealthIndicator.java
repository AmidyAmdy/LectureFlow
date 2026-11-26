package ru.filatov.lectureflow.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final AudioStorageService audioStorageService;

    public StorageHealthIndicator(AudioStorageService audioStorageService) {
        this.audioStorageService = audioStorageService;
    }

    @Override
    public Health health() {
        try {
            audioStorageService.checkBucket();
            return Health.up().withDetail("bucket", audioStorageService.getBucket()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("bucket", audioStorageService.getBucket())
                    .withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }
}
