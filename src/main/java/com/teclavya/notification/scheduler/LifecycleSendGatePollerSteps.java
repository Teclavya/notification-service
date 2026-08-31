package com.teclavya.notification.scheduler;

import com.teclavya.notification.entities.NotificationPreference;
import com.teclavya.notification.entities.NotificationType;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
import com.teclavya.notification.lifecycle.verifier.ContentSafetyVerifier;
import com.teclavya.notification.lifecycle.verifier.SafetyVerdict;
import com.teclavya.notification.repo.NotificationPreferenceRepository;
import com.teclavya.notification.service.NotificationService;
import com.teclavya.notification.service.QuietHoursEvaluator;
import com.teclavya.notification.lifecycle.analytics.LifecycleEventEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The four {@code @Transactional} claim+process steps of the ethical send-gate poller,
 * isolated on their own injected Spring bean (NS-BE-4 SEV-1 fix).
 *
 * <p><b>Why a separate bean:</b> Spring's {@code @Transactional} works by wrapping the bean in
 * an AOP proxy; a method call only goes through that proxy — and only then does the
 * transaction actually open — when it arrives from OUTSIDE the bean. A self-invocation
 * ({@code this.processDrafted()} called from another method on the SAME bean, as
 * {@link LifecycleSendGatePoller#poll()} used to do) bypasses the proxy entirely, so the
 * {@code @Transactional} annotation is silently inert. Since every claim query in
 * {@link LifecycleMessageReviewQueueRepository} uses {@code SELECT ... FOR UPDATE SKIP LOCKED},
 * an inert transaction meant the row lock was released the instant the claim query returned
 * instead of being held for the whole claim+verify+approve+send batch — in a multi-instance
 * deployment, two concurrent pollers could then claim and process the SAME row, delivering the
 * same notification to a student twice.
 *
 * <p>The fix (mirrors {@code JourneyReminderDedupClaimTxOp}'s established split-bean pattern on
 * the learning-progress-tracker side of this same feature): each step is a public method on
 * this DEDICATED bean. {@link LifecycleSendGatePoller#poll()} calls through the injected
 * {@code LifecycleSendGatePollerSteps} proxy — a cross-bean call, which Spring's AOP proxy DOES
 * intercept — so {@code @Transactional} genuinely engages per step, and the SKIP-LOCKED row
 * lock is held for that step's entire claim+process batch, exactly as the design intends.
 *
 * <p>Each step keeps its OWN transaction (not one shared transaction for the whole tick): this
 * bounds how long any one lock is held to a single step's batch rather than serializing all
 * four steps' worth of work behind one long-lived transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleSendGatePollerSteps {

    /** Bounded batch size per poller tick — cheap, indexed reads (design §13). */
    static final int BATCH_LIMIT = LifecycleSendGatePoller.BATCH_LIMIT;

    private static final String TIER_AMBER = "AMBER";
    private static final String SUPPRESS_REASON_OPT_OUT = "opt-out";
    private static final int DEFAULT_QUIET_HOURS_END = 7;

    private final LifecycleMessageReviewQueueRepository repository;
    private final LifecycleQueueService lifecycleQueueService;
    private final ContentSafetyVerifier contentSafetyVerifier;
    private final NotificationPreferenceRepository preferenceRepository;
    private final QuietHoursEvaluator quietHoursEvaluator;
    private final NotificationService notificationService;
    private final LifecycleEventEmitter lifecycleEventEmitter;

    // ------------------------------------------------------------------
    // Step A — DRAFTED: verify → approve
    // ------------------------------------------------------------------

    @Transactional
    public void processDrafted() {
        List<LifecycleMessageReviewQueue> claimed = repository.claimDraftedBatch(BATCH_LIMIT);
        for (LifecycleMessageReviewQueue row : claimed) {
            try {
                verifyAndApprove(row);
            } catch (Exception ex) {
                // Defensive: never let one bad row abort the batch (design Edge-Case —
                // "poller skips that row gracefully, other rows still process").
                log.warn("Lifecycle send-gate: id={} unexpected error in verify/approve step, "
                        + "skipping this tick — {}", row.getId(), String.valueOf(ex));
            }
        }
    }

    private void verifyAndApprove(LifecycleMessageReviewQueue row) {
        UUID id = row.getId();
        SafetyVerdict verdict = safeVerify(row);

        lifecycleQueueService.applyVerdict(id, verdict.pass(), verdict.details());
        if (!verdict.pass()) {
            lifecycleEventEmitter.emitSafetyRejected(row.getStudentId(), id, verdict.details());
            lifecycleEventEmitter.emitSendBlocked(row.getStudentId(), id, "SAFETY_REJECTED");
            log.info("Lifecycle send-gate: id={} SAFETY_REJECTED — {}", id, verdict.details());
            return;
        }

        lifecycleEventEmitter.emitSafetyChecked(row.getStudentId(), id);
        lifecycleQueueService.openVetoWindow(id);

        if (!TIER_AMBER.equalsIgnoreCase(row.getTier())) {
            // RED (reserved, P2/out-of-scope this increment): no auto-approve; left at
            // AWAITING_VETO_WINDOW for admin review or the step-D backlog safety net.
            return;
        }

        if (isOptedOut(row)) {
            lifecycleQueueService.veto(id, SUPPRESS_REASON_OPT_OUT);
            lifecycleEventEmitter.emitVetoed(row.getStudentId(), id, SUPPRESS_REASON_OPT_OUT);
            log.info("Lifecycle send-gate: id={} VETOED (opt-out at approve)", id);
            return;
        }

        lifecycleQueueService.approve(id);
        lifecycleEventEmitter.emitApproved(row.getStudentId(), id);
        log.info("Lifecycle send-gate: id={} APPROVED (AMBER, veto-window=0)", id);
    }

    /**
     * Fail-closed at the poller boundary too: {@link ContentSafetyVerifier} contractually
     * never throws, but if it ever did, this still resolves to FAIL rather than propagating
     * (defence-in-depth for AC-8.1).
     */
    private SafetyVerdict safeVerify(LifecycleMessageReviewQueue row) {
        try {
            return contentSafetyVerifier.verify(row.getMessageBody(), row.getNotificationType());
        } catch (Exception ex) {
            log.warn("Lifecycle send-gate: id={} verifier threw unexpectedly — failing closed. {}",
                    row.getId(), String.valueOf(ex));
            return SafetyVerdict.fail("verifier-threw: " + ex.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Step B — APPROVED: quiet-hours check -> deliver/markSent or markDeferred
    // ------------------------------------------------------------------

    @Transactional
    public void processApprovedForSend() {
        List<LifecycleMessageReviewQueue> claimed = repository.claimApprovedBatch(BATCH_LIMIT);
        for (LifecycleMessageReviewQueue row : claimed) {
            try {
                sendOrDefer(row);
            } catch (Exception ex) {
                log.warn("Lifecycle send-gate: id={} unexpected error in send/defer step, "
                        + "skipping this tick — {}", row.getId(), String.valueOf(ex));
            }
        }
    }

    private void sendOrDefer(LifecycleMessageReviewQueue row) {
        UUID id = row.getId();
        Optional<NotificationPreference> pref = loadPreference(row);

        if (quietHoursEvaluator.isQuiet(pref, row.getTimezone(), Instant.now())) {
            Instant nextWindowOpen = computeNextWindowOpen(pref, row.getTimezone());
            lifecycleQueueService.markDeferred(id, nextWindowOpen);
            lifecycleEventEmitter.emitDeferred(row.getStudentId(), id, nextWindowOpen.toString());
            log.info("Lifecycle send-gate: id={} DEFERRED until={} (quiet hours)", id, nextWindowOpen);
            return;
        }

        if (isOptedOutValue(pref)) {
            // Mid-flight opt-out (post-approve, Edge-Case): never delivered.
            lifecycleQueueService.markSuppressed(id, SUPPRESS_REASON_OPT_OUT);
            lifecycleEventEmitter.emitVetoed(row.getStudentId(), id, SUPPRESS_REASON_OPT_OUT);
            log.info("Lifecycle send-gate: id={} SUPPRESSED (opt-out mid-flight)", id);
            return;
        }

        deliverAndMarkSent(row);
    }

    // ------------------------------------------------------------------
    // Step C — DEFERRED due: re-check quiet-hours + opt-out -> deliver/markSent
    // ------------------------------------------------------------------

    @Transactional
    public void processDueDeferrals() {
        List<LifecycleMessageReviewQueue> claimed = repository.claimDueDeferralsBatch(Instant.now(), BATCH_LIMIT);
        for (LifecycleMessageReviewQueue row : claimed) {
            try {
                drainDeferred(row);
            } catch (Exception ex) {
                log.warn("Lifecycle send-gate: id={} unexpected error in deferral-drain step, "
                        + "skipping this tick — {}", row.getId(), String.valueOf(ex));
            }
        }
    }

    private void drainDeferred(LifecycleMessageReviewQueue row) {
        UUID id = row.getId();
        Optional<NotificationPreference> pref = loadPreference(row);

        if (quietHoursEvaluator.isQuiet(pref, row.getTimezone(), Instant.now())) {
            // Window shifted again (rare edge case) — the state machine only allows
            // markDeferred() from APPROVED, so a DEFERRED row is left as-is and re-evaluated
            // on the next tick rather than re-deferred.
            log.debug("Lifecycle send-gate: id={} still in quiet hours on drain, re-check next tick", id);
            return;
        }

        if (isOptedOutValue(pref)) {
            lifecycleQueueService.markSuppressed(id, SUPPRESS_REASON_OPT_OUT);
            lifecycleEventEmitter.emitVetoed(row.getStudentId(), id, SUPPRESS_REASON_OPT_OUT);
            log.info("Lifecycle send-gate: id={} SUPPRESSED (opt-out mid-flight, deferral drain)", id);
            return;
        }

        deliverAndMarkSent(row);
    }

    // ------------------------------------------------------------------
    // Step D — expired veto window: backlog / RED safety net (ADR-10)
    // ------------------------------------------------------------------

    @Transactional
    public void processExpiredVetoWindows() {
        List<LifecycleMessageReviewQueue> claimed =
                repository.claimExpiredVetoWindowBatch(Instant.now(), BATCH_LIMIT);
        for (LifecycleMessageReviewQueue row : claimed) {
            try {
                autoApproveExpired(row);
            } catch (Exception ex) {
                log.warn("Lifecycle send-gate: id={} unexpected error in expired-veto-window step, "
                        + "skipping this tick — {}", row.getId(), String.valueOf(ex));
            }
        }
    }

    private void autoApproveExpired(LifecycleMessageReviewQueue row) {
        UUID id = row.getId();

        if (!TIER_AMBER.equalsIgnoreCase(row.getTier())) {
            // RED (reserved, P2/out-of-scope this increment): requires admin approval, not
            // touched by this backlog safety net.
            return;
        }

        if (isOptedOut(row)) {
            lifecycleQueueService.veto(id, SUPPRESS_REASON_OPT_OUT);
            lifecycleEventEmitter.emitVetoed(row.getStudentId(), id, SUPPRESS_REASON_OPT_OUT);
            log.info("Lifecycle send-gate: id={} VETOED (opt-out, expired-veto-window backlog)", id);
            return;
        }

        lifecycleQueueService.approve(id);
        lifecycleEventEmitter.emitApproved(row.getStudentId(), id);
        log.info("Lifecycle send-gate: id={} APPROVED (AMBER, expired-veto-window backlog)", id);
    }

    // ------------------------------------------------------------------
    // Delivery helper (shared by steps B and C)
    // ------------------------------------------------------------------

    /**
     * Delivers via {@link NotificationService#deliver} then marks SENT (idempotent — a second
     * call on an already-SENT row is a no-op per {@code LifecycleQueueServiceImpl.markSent()},
     * AC-8.2). A delivery failure (or any unexpected exception, e.g. a since-invalidated
     * metadata reference) is caught by the calling step method's try/catch so the row is simply
     * skipped this tick and other rows in the batch still process (design Edge-Case).
     *
     * <p>Note: {@code lifecycle_message_review_queue} does not persist the original request's
     * {@code title}/{@code actionUrl} (NS-BE-1's {@code enqueue()} only stores {@code body} +
     * {@code metadata} — no schema change in this ticket's scope, ADR-4). The title is
     * synthesized from the notification type; {@code actionUrl} is read from
     * {@code metadata.actionUrl} if the caller supplied it there, else omitted.
     */
    private void deliverAndMarkSent(LifecycleMessageReviewQueue row) {
        NotificationType type = LifecycleSendGatePoller.resolveType(row.getNotificationType());
        Map<String, Object> metadata = row.getMetadata();
        notificationService.deliver(
                row.getStudentId(), type, deriveTitle(type), row.getMessageBody(),
                metadata, extractActionUrl(metadata));
        lifecycleQueueService.markSent(row.getId());
        lifecycleEventEmitter.emitSent(row.getStudentId(), row.getId());
        log.info("Lifecycle send-gate: id={} SENT", row.getId());
    }

    private static String deriveTitle(NotificationType type) {
        String[] words = type.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static String extractActionUrl(Map<String, Object> metadata) {
        if (metadata != null && metadata.get("actionUrl") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    /**
     * Next local {@code quietHoursEnd} boundary, converted to an {@link Instant} — the defer
     * target per design §6. Mirrors {@link QuietHoursEvaluator}'s zone-resolution precedence
     * (queue timezone → preference timezone → UTC) without depending on its private helpers.
     */
    private Instant computeNextWindowOpen(Optional<NotificationPreference> pref, String queueTimezone) {
        ZoneId zone = resolveZone(queueTimezone, pref.map(NotificationPreference::getTimezone).orElse(null));
        int endHour = pref.map(NotificationPreference::getQuietHoursEnd)
                .filter(h -> h != null)
                .orElse(DEFAULT_QUIET_HOURS_END);

        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.withHour(endHour).withMinute(0).withSecond(0).withNano(0);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }

    private static ZoneId resolveZone(String queueTimezone, String preferenceTimezone) {
        ZoneId zone = tryParseZone(queueTimezone);
        if (zone != null) {
            return zone;
        }
        zone = tryParseZone(preferenceTimezone);
        if (zone != null) {
            return zone;
        }
        return ZoneId.of("UTC");
    }

    private static ZoneId tryParseZone(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(candidate.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Opt-out = a persisted {@link NotificationPreference} row for this student+type with
     * {@code inAppEnabled=false}. No persisted row ⇒ default-enabled ⇒ not opted out (mirrors
     * {@code @Builder.Default inAppEnabled=true} and the "never silently block" posture used by
     * {@link QuietHoursEvaluator} for quiet hours).
     */
    boolean isOptedOut(LifecycleMessageReviewQueue row) {
        return isOptedOutValue(loadPreference(row));
    }

    private static boolean isOptedOutValue(Optional<NotificationPreference> pref) {
        return pref.map(p -> !p.isInAppEnabled()).orElse(false);
    }

    Optional<NotificationPreference> loadPreference(LifecycleMessageReviewQueue row) {
        NotificationType type = LifecycleSendGatePoller.resolveType(row.getNotificationType());
        return preferenceRepository.findByStudentIdAndNotificationType(row.getStudentId(), type);
    }
}
