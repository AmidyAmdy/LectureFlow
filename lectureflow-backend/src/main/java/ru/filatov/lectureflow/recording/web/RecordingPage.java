package ru.filatov.lectureflow.recording.web;

import java.util.List;

public record RecordingPage(List<RecordingResponse> items, int page, int size, long totalItems, int totalPages) {
}
