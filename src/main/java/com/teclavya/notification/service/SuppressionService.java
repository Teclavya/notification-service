package com.teclavya.notification.service;

import com.teclavya.notification.entities.EmailSuppression;
import com.teclavya.notification.repo.EmailSuppressionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuppressionService {

    private final EmailSuppressionRepository suppressionRepository;

    @Value("${feature.flags.email.suppression-enabled:true}")
    private boolean suppressionEnabled;

    @Transactional(readOnly = true)
    public boolean isSuppressed(String email) {
        if (!suppressionEnabled || !StringUtils.hasText(email)) {
            return false;
        }
        boolean suppressed = suppressionRepository.existsByEmailIgnoreCaseAndRemovedAtIsNull(email.trim());
        if (suppressed) {
            log.info("email_suppressed to='{}' suppression_check=ACTIVE", email);
        }
        return suppressed;
    }

    @Transactional
    public EmailSuppression suppressEmail(String email, String suppressionType, String source, String notes) {
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        String normalizedEmail = email.trim().toLowerCase();
        Optional<EmailSuppression> existing = suppressionRepository.findByEmailIgnoreCase(normalizedEmail);

        EmailSuppression suppression;
        if (existing.isPresent()) {
            suppression = existing.get();
            suppression.setSuppressionType(suppressionType != null ? suppressionType : "HARD_BOUNCE");
            suppression.setSource(source);
            suppression.setNotes(notes);
            suppression.setRemovedAt(null); // re-activate
            suppression.setSuppressedAt(LocalDateTime.now());
        } else {
            suppression = EmailSuppression.builder()
                    .email(normalizedEmail)
                    .suppressionType(suppressionType != null ? suppressionType : "HARD_BOUNCE")
                    .source(source)
                    .notes(notes)
                    .suppressedAt(LocalDateTime.now())
                    .build();
        }

        log.warn("email_suppression_upserted to='{}' type='{}' source='{}'",
                normalizedEmail, suppression.getSuppressionType(), source);
        return suppressionRepository.save(suppression);
    }

    @Transactional
    public void removeSuppression(String email) {
        if (!StringUtils.hasText(email)) {
            return;
        }
        suppressionRepository.findByEmailIgnoreCaseAndRemovedAtIsNull(email.trim().toLowerCase())
                .ifPresent(s -> {
                    s.setRemovedAt(LocalDateTime.now());
                    suppressionRepository.save(s);
                    log.info("email_suppression_removed to='{}'", email);
                });
    }
}
