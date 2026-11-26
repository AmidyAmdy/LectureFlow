package ru.filatov.lectureflow.recording;

public class TranscriptionNotReadyException extends RuntimeException {

    public TranscriptionNotReadyException(Long id) {
        super("Транскрипция ещё не готова для записи: " + id);
    }
}
