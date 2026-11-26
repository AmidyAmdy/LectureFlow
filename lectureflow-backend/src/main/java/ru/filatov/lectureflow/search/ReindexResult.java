package ru.filatov.lectureflow.search;

import java.util.List;

public record ReindexResult(int recordings, int indexedRecordings, int indexedSegments, List<String> failures) {
}
