package ru.filatov.lectureflow.processing.messaging;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

@Service
public class TranscriptionPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 10;

    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;

    public TranscriptionPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
    }

    public void publish(TranscriptionTask task) {
        String json = jsonMapper.writeValueAsString(task);
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend("", RabbitConfig.TRANSCRIPTION_QUEUE, json, correlationData);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Ожидание подтверждения RabbitMQ прервано", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AmqpException("RabbitMQ не подтвердил получение задания", e);
        }
        if (!confirm.ack()) {
            throw new AmqpException("RabbitMQ отклонил задание: " + confirm.reason());
        }

        if (correlationData.getReturned() != null) {
            throw new AmqpException("Очередь " + RabbitConfig.TRANSCRIPTION_QUEUE + " не найдена");
        }
    }
}
