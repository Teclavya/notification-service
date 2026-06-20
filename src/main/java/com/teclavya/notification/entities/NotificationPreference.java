package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
@Table(name = "notification_preference", uniqueConstraints = @UniqueConstraint(columnNames = {"studentId", "notificationType"}))
public class NotificationPreference {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID preferenceId;
    @Column(nullable = false)
    private String studentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType notificationType;
    @Builder.Default
    private boolean inAppEnabled = true;
    @Builder.Default
    private boolean emailEnabled = true;
    @Builder.Default
    private boolean pushEnabled = true;
    @Builder.Default
    private Integer quietHoursStart = 22;
    @Builder.Default
    private Integer quietHoursEnd = 7;
    @Builder.Default
    private boolean quietHoursEnabled = true;
    private String timezone;
}
