package com.teclavya.notification.lifecycle.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the V3 lifecycle_message_review_queue table.
 *
 * Column names are snake_case and matched via Spring Boot's default
 * SpringPhysicalNamingStrategy (camelCase → snake_case), so explicit
 * @Column(name=…) is only added where the field name would not produce
 * the correct snake_case column name automatically.
 */
@Entity
@Table(name = "lifecycle_message_review_queue")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LifecycleMessageReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(name = "template_id")
    private String templateId;

    @Column(name = "message_body", nullable = false, columnDefinition = "text")
    private String messageBody;

    /**
     * Autonomy tier — stored as VARCHAR; only AMBER is in-scope for P1.
     * Stored as String to avoid an enum change when RED is added in P2.
     */
    @Column(name = "tier", nullable = false, length = 10)
    @Builder.Default
    private String tier = "AMBER";

    /**
     * State machine status — must be the Java enum for type-safe transitions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private LifecycleMessageStatus status = LifecycleMessageStatus.DRAFTED;

    /**
     * Safety verdict — stored as plain VARCHAR (PASS | FAIL | null).
     * Kept as String to avoid an enum and allow null for "not yet evaluated".
     */
    @Column(name = "safety_verdict", length = 8)
    private String safetyVerdict;

    @Column(name = "safety_details", columnDefinition = "text")
    private String safetyDetails;

    @Column(name = "veto_window_expires_at")
    private Instant vetoWindowExpiresAt;

    @Column(name = "deferred_until")
    private Instant deferredUntil;

    /**
     * Authoritative student timezone extracted from enqueue metadata.
     * Nullable — not all callers populate it.
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "veto_reason", columnDefinition = "text")
    private String vetoReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;
}
