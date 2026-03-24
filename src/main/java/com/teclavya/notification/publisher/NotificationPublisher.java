package com.teclavya.notification.publisher;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.dto.response.NotificationDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Publishes an IN_APP notification to Redis so the
     * AIChatbotService WebSocket hub can push it to the connected browser.
     * Channel format: notifications:{studentId}
     */
    public void publishToWebSocket(NotificationDto notification, String studentId) {
        String channel = "notifications:" + studentId;
        try {
            String payload = objectMapper.writeValueAsString(notification);
            redisTemplate.convertAndSend(channel, payload);
            log.debug("Published notification to Redis channel '{}'", channel);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification for Redis channel '{}'", channel, e);
        } catch (Exception e) {
            log.error("Failed to publish notification to Redis channel '{}'", channel, e);
        }
    }
}