package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity @Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
@Table(name = "notification")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID notificationId;
    @Column(nullable = false)
    private String studentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType notificationType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;
    @Column(nullable = false, length = 500)
    private String title;
    @Column(columnDefinition = "text")
    private String body;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
    @Builder.Default
    private boolean isRead = false;
    @Builder.Default
    private boolean isSent = false;
    private LocalDateTime sentAt;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime expiresAt;
}
