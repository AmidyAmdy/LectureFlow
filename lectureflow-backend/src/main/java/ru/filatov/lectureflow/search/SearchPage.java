package ru.filatov.lectureflow.search;

import java.util.List;

public record SearchPage(List<SearchResult> items, int page, int size, long totalItems, int totalPages) {
}
