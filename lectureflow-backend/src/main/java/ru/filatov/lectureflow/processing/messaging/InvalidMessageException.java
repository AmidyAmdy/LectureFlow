package ru.filatov.lectureflow.processing.messaging;

public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
