package ru.filatov.lectureflow.processing.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String TRANSCRIPTION_QUEUE = "lectureflow.transcription";
    public static final String TRANSCRIPTION_COMPLETED_QUEUE = "lectureflow.transcription.completed";
    public static final String SUMMARY_COMPLETED_QUEUE = "lectureflow.summary.completed";

    public static final String TRANSCRIPTION_FAILED_QUEUE = "lectureflow.transcription.failed";

    public static final String BACKEND_FAILED_QUEUE = "lectureflow.backend.failed";

    @Bean
    public Queue transcriptionQueue() {
        return new Queue(TRANSCRIPTION_QUEUE, true);
    }

    @Bean
    public Queue transcriptionCompletedQueue() {
        return new Queue(TRANSCRIPTION_COMPLETED_QUEUE, true);
    }

    @Bean
    public Queue summaryCompletedQueue() {
        return new Queue(SUMMARY_COMPLETED_QUEUE, true);
    }

    @Bean
    public Queue transcriptionFailedQueue() {
        return new Queue(TRANSCRIPTION_FAILED_QUEUE, true);
    }

    @Bean
    public Queue backendFailedQueue() {
        return new Queue(BACKEND_FAILED_QUEUE, true);
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, "", BACKEND_FAILED_QUEUE);
    }
}
