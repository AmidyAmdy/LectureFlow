package ru.filatov.lectureflow.recording.web;

public class RangeNotSatisfiableException extends RuntimeException {

    private final long fileSize;

    public RangeNotSatisfiableException(long fileSize) {
        super("Запрошенный диапазон выходит за пределы файла размером " + fileSize + " байт");
        this.fileSize = fileSize;
    }

    public long getFileSize() {
        return fileSize;
    }
}
