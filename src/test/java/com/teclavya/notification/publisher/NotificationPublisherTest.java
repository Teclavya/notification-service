package com.teclavya.notification.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teclavya.notification.dto.response.NotificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private NotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        publisher = new NotificationPublisher(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("should publish to correct Redis channel")
    void shouldPublishToCorrectChannel() {
        NotificationDto dto = NotificationDto.builder()
                .notificationId("uuid-123")
                .notificationType("MENTOR_REPLIED")
                .channel("IN_APP")
                .title("Test")
                .body("Body")
                .actionUrl("/mentor/1")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();

        publisher.publishToWebSocket(dto, "student-123");

        verify(redisTemplate).convertAndSend(
                eq("notifications:student-123"),
                contains("MENTOR_REPLIED")
        );
    }

    @Test
    @DisplayName("should not throw when Redis fails")
    void shouldNotThrowWhenRedisFails() {
        doThrow(new RuntimeException("Redis down"))
                .when(redisTemplate).convertAndSend(any(String.class), any(String.class));

        NotificationDto dto = NotificationDto.builder()
                .notificationId("uuid-123")
                .notificationType("GENERAL")
                .channel("IN_APP")
                .title("Test")
                .body("Body")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();

        assertDoesNotThrow(() -> publisher.publishToWebSocket(dto, "student-123"));
    }
}