package com.teclavya.notification.lifecycle.analytics;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for the V3 {@code lifecycle_event} table.
 *
 * <p>Column mapping follows SpringPhysicalNamingStrategy (camelCase → snake_case),
 * matching the {@code LifecycleMessageReviewQueue} entity pattern.
 *
 * <p>The {@code properties} column uses the same {@code @JdbcTypeCode(SqlTypes.JSON)}
 * approach as the review-queue entity so that JSONB works on PostgreSQL and clob works
 * on H2 (via {@code H2JsonbDialect}).
 */
@Entity
@Table(name = "lifecycle_event")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Canonical event type string — e.g. {@code lifecycle_message.send_blocked}, {@code feature_flag.updated}. */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    /** Optional reference to the lifecycle_message_review_queue row that triggered this event. */
    @Column(name = "message_id")
    private UUID messageId;

    /** Arbitrary key-value properties stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "properties")
    private Map<String, Object> properties;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
