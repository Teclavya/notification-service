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
@Table(name = "email_suppression")
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 320, unique = true)
    private String email;

    @Column(name = "suppression_type", nullable = false, length = 20)
    private String suppressionType;

    @Builder.Default
    @Column(name = "suppressed_at", nullable = false)
    private LocalDateTime suppressedAt = LocalDateTime.now();

    @Column(length = 100)
    private String source;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;
}
