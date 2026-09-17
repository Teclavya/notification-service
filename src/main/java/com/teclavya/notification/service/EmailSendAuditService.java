package com.teclavya.notification.service;

import com.teclavya.notification.entities.EmailSendEvent;
import com.teclavya.notification.repo.EmailSendEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSendAuditService {

    private final EmailSendEventRepository sendEventRepository;

    @Transactional(readOnly = true)
    public Optional<EmailSendEvent> findByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }
        return sendEventRepository.findByIdempotencyKey(idempotencyKey.trim());
    }

    @Transactional
    public EmailSendEvent recordPending(String toEmail, String subject, String templateId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        EmailSendEvent event = EmailSendEvent.builder()
                .toEmail(toEmail)
                .subject(subject)
                .templateId(templateId)
                .idempotencyKey(idempotencyKey.trim())
                .status("PENDING")
                .attemptCount(1)
                .createdAt(LocalDateTime.now())
                .build();
        return sendEventRepository.save(event);
    }

    @Transactional
    public void recordSent(EmailSendEvent event, String providerMessageId) {
        if (event == null) {
            return;
        }
        event.setStatus("SENT");
        event.setProviderMessageId(providerMessageId);
        event.setSentAt(LocalDateTime.now());
        sendEventRepository.save(event);
        log.info("email_sent to='{}' subject='{}' templateId='{}'",
                event.getToEmail(), event.getSubject(), event.getTemplateId());
    }

    @Transactional
    public void recordFailed(EmailSendEvent event, String errorDetail) {
        if (event == null) {
            return;
        }
        event.setStatus("FAILED");
        event.setErrorDetail(errorDetail);
        sendEventRepository.save(event);
        log.error("email_send_failed to='{}' attempt={} error='{}'",
                event.getToEmail(), event.getAttemptCount(), errorDetail);
    }

    @Transactional
    public void recordSuppressed(String toEmail, String subject, String templateId, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        EmailSendEvent event = EmailSendEvent.builder()
                .toEmail(toEmail)
                .subject(subject)
                .templateId(templateId)
                .idempotencyKey(idempotencyKey.trim())
                .status("SUPPRESSED")
                .attemptCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        sendEventRepository.save(event);
    }

    @Transactional
    public void incrementAttempt(EmailSendEvent event) {
        if (event == null) {
            return;
        }
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setStatus("PENDING");
        sendEventRepository.save(event);
    }
}
