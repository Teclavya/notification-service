package com.teclavya.notification.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "processed_ticket_events")
public class ProcessedTicketEvent {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "ticket_number", nullable = false, length = 32)
    private String ticketNumber;

    @Builder.Default
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();
}
