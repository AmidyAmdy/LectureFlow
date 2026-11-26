package ru.filatov.lectureflow.recording;

public class RecordingNotFoundException extends RuntimeException {

    public RecordingNotFoundException(Long id) {
        super("Запись не найдена: " + id);
    }
}
