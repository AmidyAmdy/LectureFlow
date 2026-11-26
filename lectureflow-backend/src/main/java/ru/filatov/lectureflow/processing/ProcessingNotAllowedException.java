package ru.filatov.lectureflow.processing;

public class ProcessingNotAllowedException extends RuntimeException {

    public ProcessingNotAllowedException(String message) {
        super(message);
    }
}
