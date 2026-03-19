package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
@Table(name = "push_subscription")
public class PushSubscription {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID subscriptionId;
    @Column(nullable = false)
    private String studentId;
    @Column(columnDefinition = "text", nullable = false)
    private String endpoint;
    @Column(columnDefinition = "text")
    private String p256dhKey;
    @Column(columnDefinition = "text")
    private String authKey;
    @Column(length = 500)
    private String userAgent;
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastUsedAt;
    @Builder.Default
    private boolean active = true;
}
