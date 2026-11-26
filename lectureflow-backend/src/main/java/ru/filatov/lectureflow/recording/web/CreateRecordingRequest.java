package ru.filatov.lectureflow.recording.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRecordingRequest(
        @NotBlank(message = "Введите название записи")
        @Size(max = 255, message = "Название должно быть не длиннее 255 символов")
        String title) {
}
