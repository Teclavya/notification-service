package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "oncall_dispatch_audit")
public class OnCallDispatchAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", nullable = false, length = 32)
    private String ticketNumber;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "recipient_or_url", length = 512)
    private String recipientOrUrl;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Builder.Default
    @Column(name = "dispatched_at", nullable = false)
    private Instant dispatchedAt = Instant.now();
}
