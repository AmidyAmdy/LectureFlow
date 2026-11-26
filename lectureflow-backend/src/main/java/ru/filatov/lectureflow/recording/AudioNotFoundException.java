package ru.filatov.lectureflow.recording;

public class AudioNotFoundException extends RuntimeException {

    public AudioNotFoundException(Long id) {
        super("У записи " + id + " ещё нет аудио");
    }
}
