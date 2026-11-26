package ru.filatov.lectureflow.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ru.filatov.lectureflow.processing.InvalidAudioException;
import ru.filatov.lectureflow.processing.ProcessingNotAllowedException;
import ru.filatov.lectureflow.processing.messaging.TaskNotQueuedException;
import ru.filatov.lectureflow.recording.AudioNotFoundException;
import ru.filatov.lectureflow.recording.RecordingNotFoundException;
import ru.filatov.lectureflow.recording.SummaryNotReadyException;
import ru.filatov.lectureflow.recording.TranscriptionNotReadyException;
import ru.filatov.lectureflow.recording.web.RangeNotSatisfiableException;
import ru.filatov.lectureflow.search.SearchIndexingException;
import ru.filatov.lectureflow.search.SearchUnavailableException;
import ru.filatov.lectureflow.storage.StorageUnavailableException;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RecordingNotFoundException.class)
    public ResponseEntity<ApiError> handleRecordingNotFound(RecordingNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AudioNotFoundException.class)
    public ResponseEntity<ApiError> handleAudioNotFound(AudioNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(NoSuchKeyException.class)
    public ResponseEntity<ApiError> handleMissingObject(NoSuchKeyException e, HttpServletRequest request) {
        log.warn("Объект не найден в RustFS при запросе {}: {}", request.getRequestURI(), e.getMessage());
        return error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND",
                "Файл записи не найден в хранилище RustFS", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Адрес не найден", request);
    }

    @ExceptionHandler(TranscriptionNotReadyException.class)
    public ResponseEntity<ApiError> handleTranscriptionNotReady(TranscriptionNotReadyException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "TRANSCRIPTION_NOT_READY", e.getMessage(), request);
    }

    @ExceptionHandler(SummaryNotReadyException.class)
    public ResponseEntity<ApiError> handleSummaryNotReady(SummaryNotReadyException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "SUMMARY_NOT_READY", e.getMessage(), request);
    }

    @ExceptionHandler(ProcessingNotAllowedException.class)
    public ResponseEntity<ApiError> handleProcessingNotAllowed(ProcessingNotAllowedException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "PROCESSING_NOT_ALLOWED", e.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleVersionConflict(OptimisticLockingFailureException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "Запись одновременно изменилась. Обновите страницу и повторите действие.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            details.add(fieldError.getField() + ": " + fieldError.getDefaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstOrDefault(details), details, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleInvalidParameters(HandlerMethodValidationException e, HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        for (MessageSourceResolvable validationError : e.getAllErrors()) {
            details.add(validationError.getDefaultMessage());
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstOrDefault(details), details, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Неверное значение параметра " + e.getName() + ": " + e.getValue(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Не указан параметр " + e.getParameterName(), request);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Не выбран файл: передайте его в поле " + e.getRequestPartName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Тело запроса должно быть JSON-объектом, например {\"title\": \"Лекция 1\"}", request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage(), request);
    }

    @ExceptionHandler(InvalidAudioException.class)
    public ResponseEntity<ApiError> handleInvalidAudio(InvalidAudioException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", e.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "Файл слишком большой. Максимальный размер задан в LECTUREFLOW_MAX_UPLOAD_SIZE.", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> handleMultipart(MultipartException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Файл нужно отправить формой multipart/form-data в поле file", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Метод " + e.getMethod() + " не поддерживается для этого адреса", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Неподдерживаемый Content-Type: " + e.getContentType(), request);
    }

    @ExceptionHandler(RangeNotSatisfiableException.class)
    public ResponseEntity<ApiError> handleRange(RangeNotSatisfiableException e, HttpServletRequest request) {
        ApiError body = apiError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "RANGE_NOT_SATISFIABLE",
                e.getMessage(), List.of(), request);
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + e.getFileSize())
                .body(body);
    }

    @ExceptionHandler(TaskNotQueuedException.class)
    public ResponseEntity<ApiError> handleTaskNotQueued(TaskNotQueuedException e, HttpServletRequest request) {
        return unavailable("QUEUE_UNAVAILABLE", e.getMessage(), e, request);
    }

    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<ApiError> handleAmqp(AmqpException e, HttpServletRequest request) {
        return unavailable("QUEUE_UNAVAILABLE", "RabbitMQ недоступен. Повторите попытку позже.", e, request);
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ApiError> handleStorageUnavailable(StorageUnavailableException e, HttpServletRequest request) {
        return unavailable("STORAGE_UNAVAILABLE", e.getMessage(), e, request);
    }

    @ExceptionHandler(SdkException.class)
    public ResponseEntity<ApiError> handleStorage(SdkException e, HttpServletRequest request) {
        return unavailable("STORAGE_UNAVAILABLE", "Хранилище файлов RustFS недоступно. Повторите попытку позже.", e, request);
    }

    @ExceptionHandler(SearchUnavailableException.class)
    public ResponseEntity<ApiError> handleSearchUnavailable(SearchUnavailableException e, HttpServletRequest request) {
        return unavailable("SEARCH_UNAVAILABLE", e.getMessage(), e, request);
    }

    @ExceptionHandler({RestClientException.class, SearchIndexingException.class})
    public ResponseEntity<ApiError> handleSearch(RuntimeException e, HttpServletRequest request) {
        return unavailable("SEARCH_UNAVAILABLE", "Поиск OpenSearch недоступен. Повторите попытку позже.", e, request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiError> handleDatabase(RuntimeException e, HttpServletRequest request) {
        return unavailable("DATABASE_UNAVAILABLE", "База данных PostgreSQL недоступна. Повторите попытку позже.", e, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e, HttpServletRequest request) {
        log.error("Непредвиденная ошибка при запросе {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Внутренняя ошибка сервера. Подробности — в логе backend.", request);
    }

    private ResponseEntity<ApiError> unavailable(String code, String message, Exception e, HttpServletRequest request) {
        log.warn("{} при запросе {} {}: {}", code, request.getMethod(), request.getRequestURI(), e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, code, message, request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return error(status, code, message, List.of(), request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, List<String> details,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(apiError(status, code, message, details, request));
    }

    private ApiError apiError(HttpStatus status, String code, String message, List<String> details,
            HttpServletRequest request) {
        return new ApiError(status.value(), code, message, details, request.getRequestURI(), Instant.now());
    }

    private String firstOrDefault(List<String> details) {
        if (details.isEmpty()) {
            return "Запрос заполнен неверно";
        }
        return details.get(0);
    }
}
