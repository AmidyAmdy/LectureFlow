package ru.filatov.lectureflow.search;

public record SearchDocument(Long recordingId, String audioObjectKey, double start, double end, String text) {

}
