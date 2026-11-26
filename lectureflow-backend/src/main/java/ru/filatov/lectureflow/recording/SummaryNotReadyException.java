package ru.filatov.lectureflow.recording;

public class SummaryNotReadyException extends RuntimeException {

    public SummaryNotReadyException(Long id) {
        super("Конспекты ещё не готовы для записи: " + id);
    }
}
