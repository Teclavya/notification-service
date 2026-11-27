package com.teclavya.notification.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "public_enquiries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicEnquiry {

    @Id
    private UUID id;

    @Column(name = "reference_id", nullable = false, unique = true, length = 32)
    private String referenceId;

    @Column(nullable = false, length = 32)
    private String topic;

    @Column(name = "target_alias", nullable = false, length = 128)
    private String targetAlias;

    @Column(name = "sender_name", nullable = false, length = 100)
    private String senderName;

    @Column(name = "sender_email", nullable = false, length = 254)
    private String senderEmail;

    @Column(name = "message_body", nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    @Column(name = "client_ip_hash", length = 64)
    private String clientIpHash;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
