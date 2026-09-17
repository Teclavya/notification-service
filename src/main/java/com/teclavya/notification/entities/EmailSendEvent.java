package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "email_send_event")
public class EmailSendEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "to_email", nullable = false, length = 320)
    private String toEmail;

    @Column(length = 500)
    private String subject;

    @Column(name = "template_id", length = 100)
    private String templateId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Builder.Default
    @Column(name = "soft_bounce_count", nullable = false)
    private int softBounceCount = 0;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "error_detail", columnDefinition = "text")
    private String errorDetail;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
