package com.teclavya.notification.dto.response;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class NotificationDto {
    private String notificationId;
    private String notificationType;
    private String channel;
    private String title;
    private String body;
    private Map<String, Object> metadata;
    private boolean read;
    private LocalDateTime createdAt;
}
