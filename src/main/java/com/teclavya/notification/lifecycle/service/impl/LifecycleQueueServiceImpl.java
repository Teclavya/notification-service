package com.teclavya.notification.lifecycle.service.impl;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LifecycleQueueServiceImpl implements LifecycleQueueService {

    static final long VETO_WINDOW_MINUTES = 5L;

    private static final String VERDICT_PASS = "PASS";
    private static final String VERDICT_FAIL = "FAIL";

    private final LifecycleMessageReviewQueueRepository repository;

    // ------------------------------------------------------------------
    // enqueue
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue enqueue(InternalSendRequest req) {
        Map<String, Object> meta = req.getMetadata();

        String timezone = null;
        String tier = "AMBER";

        if (meta != null) {
            Object tz = meta.get("timezone");
            if (tz instanceof String s && !s.isBlank()) {
                timezone = s;
            }
            Object tierVal = meta.get("tier");
            if (tierVal instanceof String s && !s.isBlank()) {
                tier = s.toUpperCase();
            }
        }

        LifecycleMessageReviewQueue row = LifecycleMessageReviewQueue.builder()
                .studentId(req.getStudentId())
                .notificationType(req.getNotificationType())
                .messageBody(req.getBody() != null ? req.getBody() : "")
                .status(LifecycleMessageStatus.DRAFTED)
                .tier(tier)
                .timezone(timezone)
                .metadata(meta)
                .build();

        LifecycleMessageReviewQueue saved = repository.save(row);
        log.debug("Lifecycle enqueued id={} student={} type={}", saved.getId(), saved.getStudentId(), saved.getNotificationType());
        return saved;
    }

    // ------------------------------------------------------------------
    // applyVerdict
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue applyVerdict(UUID id, boolean pass, String details) {
        LifecycleMessageReviewQueue row = load(id);

        row.setSafetyDetails(details);

        if (pass) {
            row.setSafetyVerdict(VERDICT_PASS);
            // Persist SAFETY_CHECKED as a real, distinct state (AC-2.2, AC-3.2, GOLDEN-01 step 2).
            // Caller must follow up with openVetoWindow(id) to advance to AWAITING_VETO_WINDOW.
            row.setStatus(LifecycleMessageStatus.SAFETY_CHECKED);
            row.setVetoWindowExpiresAt(Instant.now().plus(VETO_WINDOW_MINUTES, ChronoUnit.MINUTES));
            log.debug("Safety PASS id={} status=SAFETY_CHECKED veto_expires={}", id, row.getVetoWindowExpiresAt());
        } else {
            row.setSafetyVerdict(VERDICT_FAIL);
            row.setStatus(LifecycleMessageStatus.SAFETY_REJECTED);
            row.setVetoWindowExpiresAt(null);
            log.debug("Safety FAIL id={}", id);
        }

        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // openVetoWindow
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue openVetoWindow(UUID id) {
        LifecycleMessageReviewQueue row = load(id);

        if (row.getStatus() != LifecycleMessageStatus.SAFETY_CHECKED) {
            throw new IllegalStateException(
                    "Cannot open veto window for message in state " + row.getStatus() +
                    " (id=" + id + "). Only SAFETY_CHECKED messages may transition to AWAITING_VETO_WINDOW.");
        }
        if (!VERDICT_PASS.equals(row.getSafetyVerdict())) {
            throw new IllegalStateException(
                    "Cannot open veto window for message id=" + id +
                    " with safety_verdict=" + row.getSafetyVerdict() +
                    ". Verdict must be PASS.");
        }

        row.setStatus(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
        log.debug("Veto window opened id={}", id);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // approve
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue approve(UUID id) {
        LifecycleMessageReviewQueue row = load(id);

        // CRITICAL invariant: no APPROVED without PASS verdict AND correct prior state.
        // AC-2.4: admin may approve a SAFETY_CHECKED message early (before veto window opens).
        if (row.getStatus() != LifecycleMessageStatus.AWAITING_VETO_WINDOW
                && row.getStatus() != LifecycleMessageStatus.SAFETY_CHECKED) {
            throw new IllegalStateException(
                    "Cannot approve message in state " + row.getStatus() +
                    " (id=" + id + "). Only SAFETY_CHECKED or AWAITING_VETO_WINDOW messages may be approved.");
        }
        if (!VERDICT_PASS.equals(row.getSafetyVerdict())) {
            throw new IllegalStateException(
                    "Cannot approve message id=" + id +
                    " with safety_verdict=" + row.getSafetyVerdict() +
                    ". Verdict must be PASS.");
        }

        row.setStatus(LifecycleMessageStatus.APPROVED);
        log.debug("Approved id={}", id);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // veto
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue veto(UUID id, String reason) {
        LifecycleMessageReviewQueue row = load(id);

        if (row.getStatus() != LifecycleMessageStatus.AWAITING_VETO_WINDOW
                && row.getStatus() != LifecycleMessageStatus.SAFETY_REJECTED) {
            throw new IllegalStateException(
                    "Cannot veto message in state " + row.getStatus() +
                    " (id=" + id + "). Only AWAITING_VETO_WINDOW or SAFETY_REJECTED messages may be vetoed.");
        }

        row.setStatus(LifecycleMessageStatus.VETOED);
        row.setVetoReason(reason);
        log.debug("Vetoed id={} reason={}", id, reason);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // editBody
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue editBody(UUID id, String body) {
        LifecycleMessageReviewQueue row = load(id);

        if (row.getStatus() != LifecycleMessageStatus.AWAITING_VETO_WINDOW
                && row.getStatus() != LifecycleMessageStatus.SAFETY_CHECKED
                && row.getStatus() != LifecycleMessageStatus.SAFETY_REJECTED) {
            throw new IllegalStateException(
                    "Cannot edit body of message in state " + row.getStatus() +
                    " (id=" + id + "). Only SAFETY_CHECKED, AWAITING_VETO_WINDOW, or SAFETY_REJECTED messages may be edited.");
        }

        row.setMessageBody(body);
        // Reset to DRAFTED so content-safety verifier re-runs
        row.setStatus(LifecycleMessageStatus.DRAFTED);
        row.setSafetyVerdict(null);
        row.setSafetyDetails(null);
        row.setVetoWindowExpiresAt(null);
        log.debug("Body edited, reset to DRAFTED id={}", id);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // markSent
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue markSent(UUID id) {
        LifecycleMessageReviewQueue row = load(id);

        // Idempotent
        if (row.getStatus() == LifecycleMessageStatus.SENT) {
            log.debug("markSent no-op (already SENT) id={}", id);
            return row;
        }

        // CRITICAL invariant: SENT is only reachable from APPROVED or DEFERRED
        // (DEFERRED rows that come back from the poller are re-approved, but the
        //  poller may call markSent directly for DEFERRED rows that have passed
        //  quiet hours — we allow DEFERRED here as a direct path).
        if (row.getStatus() != LifecycleMessageStatus.APPROVED
                && row.getStatus() != LifecycleMessageStatus.DEFERRED) {
            throw new IllegalStateException(
                    "Cannot mark as SENT message in state " + row.getStatus() +
                    " (id=" + id + "). Only APPROVED or DEFERRED messages may be sent.");
        }

        // Safety invariant double-check: SENT must not be reachable without PASS
        if (!VERDICT_PASS.equals(row.getSafetyVerdict())) {
            throw new IllegalStateException(
                    "Safety invariant violation: cannot mark SENT message id=" + id +
                    " with safety_verdict=" + row.getSafetyVerdict());
        }

        row.setStatus(LifecycleMessageStatus.SENT);
        row.setSentAt(Instant.now());
        log.debug("Marked SENT id={}", id);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // markDeferred
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue markDeferred(UUID id, Instant deferredUntil) {
        LifecycleMessageReviewQueue row = load(id);

        if (row.getStatus() != LifecycleMessageStatus.APPROVED) {
            throw new IllegalStateException(
                    "Cannot defer message in state " + row.getStatus() +
                    " (id=" + id + "). Only APPROVED messages may be deferred.");
        }

        row.setStatus(LifecycleMessageStatus.DEFERRED);
        row.setDeferredUntil(deferredUntil);
        log.debug("Deferred id={} until={}", id, deferredUntil);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // markSuppressed
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public LifecycleMessageReviewQueue markSuppressed(UUID id, String reason) {
        LifecycleMessageReviewQueue row = load(id);

        if (row.getStatus() != LifecycleMessageStatus.APPROVED
                && row.getStatus() != LifecycleMessageStatus.DEFERRED) {
            throw new IllegalStateException(
                    "Cannot suppress message in state " + row.getStatus() +
                    " (id=" + id + "). Only APPROVED or DEFERRED messages may be suppressed.");
        }

        row.setStatus(LifecycleMessageStatus.SUPPRESSED);
        row.setVetoReason(reason);
        log.debug("Suppressed id={} reason={}", id, reason);
        return repository.save(row);
    }

    // ------------------------------------------------------------------
    // Query helpers
    // ------------------------------------------------------------------

    @Override
    public List<LifecycleMessageReviewQueue> findDueForSend() {
        return repository.findByStatus(LifecycleMessageStatus.APPROVED);
    }

    @Override
    public List<LifecycleMessageReviewQueue> findExpiredVetoWindows() {
        return repository.findByStatusAndVetoWindowExpiresAtBefore(
                LifecycleMessageStatus.AWAITING_VETO_WINDOW, Instant.now());
    }

    @Override
    public List<LifecycleMessageReviewQueue> findDueDeferrals() {
        return repository.findByStatusAndDeferredUntilBefore(
                LifecycleMessageStatus.DEFERRED, Instant.now());
    }

    @Override
    public Page<LifecycleMessageReviewQueue> listForAdmin(LifecycleMessageStatus status, Pageable pageable) {
        return repository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private LifecycleMessageReviewQueue load(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("LifecycleMessageReviewQueue not found: " + id));
    }
}
