package ru.filatov.lectureflow.web;

import java.time.Instant;
import java.util.List;

public record ApiError(int status, String code, String message, List<String> details, String path, Instant timestamp) {
}
