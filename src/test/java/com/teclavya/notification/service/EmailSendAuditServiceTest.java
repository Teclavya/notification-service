package com.teclavya.notification.service;

import com.teclavya.notification.entities.EmailSendEvent;
import com.teclavya.notification.repo.EmailSendEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSendAuditServiceTest {

    @Mock
    private EmailSendEventRepository sendEventRepository;

    private EmailSendAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new EmailSendAuditService(sendEventRepository);
    }

    @Test
    @DisplayName("findByIdempotencyKey: returns existing record")
    void findByIdempotencyKey_returnsExisting() {
        EmailSendEvent event = EmailSendEvent.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("idem-123")
                .status("SENT")
                .build();
        when(sendEventRepository.findByIdempotencyKey("idem-123")).thenReturn(Optional.of(event));

        Optional<EmailSendEvent> result = auditService.findByIdempotencyKey("idem-123");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("recordPending: persists event with PENDING status")
    void recordPending_persistsPending() {
        when(sendEventRepository.save(any(EmailSendEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailSendEvent event = auditService.recordPending(
                "student@college.edu", "Announcement", "cohort-invite", "idem-456");

        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getIdempotencyKey()).isEqualTo("idem-456");
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordSent: transitions event to SENT and records timestamp")
    void recordSent_updatesStatusToSent() {
        EmailSendEvent event = EmailSendEvent.builder()
                .id(UUID.randomUUID())
                .status("PENDING")
                .build();

        auditService.recordSent(event, "<smtp-123@domain>");

        assertThat(event.getStatus()).isEqualTo("SENT");
        assertThat(event.getProviderMessageId()).isEqualTo("<smtp-123@domain>");
        assertThat(event.getSentAt()).isNotNull();
        verify(sendEventRepository).save(event);
    }

    @Test
    @DisplayName("recordFailed: transitions event to FAILED and records error detail")
    void recordFailed_updatesStatusToFailed() {
        EmailSendEvent event = EmailSendEvent.builder()
                .id(UUID.randomUUID())
                .status("PENDING")
                .build();

        auditService.recordFailed(event, "Connection timeout to SMTP server");

        assertThat(event.getStatus()).isEqualTo("FAILED");
        assertThat(event.getErrorDetail()).isEqualTo("Connection timeout to SMTP server");
        verify(sendEventRepository).save(event);
    }

    @Test
    @DisplayName("recordSuppressed: creates SUPPRESSED event")
    void recordSuppressed_createsSuppressedEvent() {
        auditService.recordSuppressed("blocked@domain.com", "Subject", "template", "idem-supp");

        verify(sendEventRepository).save(any(EmailSendEvent.class));
    }
}
