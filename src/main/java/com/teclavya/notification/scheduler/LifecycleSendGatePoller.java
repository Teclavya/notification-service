package com.teclavya.notification.scheduler;

import com.teclavya.notification.config.NsLifecycleFeatureFlags;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
 * Drives {@code lifecycle_message_review_queue} rows through the ethical send-gate state
 * machine — content-safety verify → AMBER auto-approve (design §2/§7, ADR-8/ADR-10).
 *
 * <p>Gated by {@link NsLifecycleFeatureFlags.SendGate#isEnabled()} (default false — AC-10.2):
 * when OFF, {@link #poll()} is a complete no-op and DRAFTED rows are left untouched.
 *
 * <p><b>Step A (this ticket, NS-BE-4a)</b> — for each claimed DRAFTED row:
 * <ol>
 *   <li>{@link ContentSafetyVerifier#verify(String, String)} (fail-closed, R4j-wrapped —
 *       never throws per contract, but this poller defensively treats any unexpected
 *       exception as FAIL too).</li>
 *   <li>PASS → {@code applyVerdict(PASS)} [SAFETY_CHECKED] → {@code openVetoWindow()}
 *       [AWAITING_VETO_WINDOW].</li>
 *   <li>AMBER tier, veto window = 0 (ADR-10): opt-out re-checked immediately — opted-out
 *       (in-app disabled for this notification type) ⇒ {@code veto("opt-out")}, never
 *       approved; otherwise {@code approve()} [APPROVED] in the same pass, without waiting
 *       on {@code veto_window_expires_at}.</li>
 *   <li>RED tier (reserved, P2/out-of-scope): left at AWAITING_VETO_WINDOW for admin review
 *       or the expired-veto-window backlog safety net (step D, NS-BE-4b).</li>
 *   <li>FAIL → {@code applyVerdict(FAIL)} [SAFETY_REJECTED] — terminal, never reachable from
 *       APPROVED/SENT.</li>
 * </ol>
 *
 * <p><b>Step B (NS-BE-4b)</b> — for each claimed APPROVED row: re-check quiet hours
 * ({@link QuietHoursEvaluator}); if quiet, {@code markDeferred(nextWindowOpen)} [DEFERRED];
 * otherwise re-check opt-out (mid-flight, post-approve) — opted-out ⇒
 * {@code markSuppressed("opt-out")} [SUPPRESSED], never delivered; otherwise
 * {@link NotificationService#deliver} then {@code markSent()} [SENT, idempotent].
 *
 * <p><b>Step C (NS-BE-4b)</b> — for each claimed DEFERRED-due row (deferred_until elapsed):
 * re-check quiet hours + opt-out exactly as step B, then deliver → markSent. If still quiet
 * (window shifted again — rare edge case), the row is left DEFERRED and re-evaluated on the
 * next tick (the state machine only allows {@code markDeferred} from APPROVED, so a DEFERRED
 * row is never re-deferred; it simply stays put until it clears quiet hours).
 *
 * <p><b>Step D (NS-BE-4b)</b> — backlog/RED safety net (design §7, ADR-10): for each claimed
 * AWAITING_VETO_WINDOW row whose veto window has expired, AMBER rows are auto-approved (with
 * the same opt-out-at-approve check as step A); RED rows (P2/out-of-scope) are left untouched.
 *
 * <p>Concurrency: every claim query ({@link LifecycleMessageReviewQueueRepository}) uses
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, and each {@code processXxx()} step method is
 * {@code @Transactional} so the row locks are held for the whole claim+process batch — a second
 * concurrent poller instance's claim query simply skips locked rows (design §8).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleSendGatePoller {

    /** Bounded batch size per poller tick — cheap, indexed reads (design §13). */
    static final int BATCH_LIMIT = 50;

    private static final String TIER_AMBER = "AMBER";
    private static final String SUPPRESS_REASON_OPT_OUT = "opt-out";
    private static final int DEFAULT_QUIET_HOURS_END = 7;

    private final LifecycleMessageReviewQueueRepository repository;
    private final LifecycleQueueService lifecycleQueueService;
    private final ContentSafetyVerifier contentSafetyVerifier;
    private final NsLifecycleFeatureFlags featureFlags;
    private final NotificationPreferenceRepository preferenceRepository;
    private final QuietHoursEvaluator quietHoursEvaluator;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        if (!featureFlags.getSendGate().isEnabled()) {
            // AC-10.2: flag OFF ⇒ zero verify/approve/send activity, rows stay wherever they are.
            return;
        }
        processDrafted();
        processApprovedForSend();
        processDueDeferrals();
        processExpiredVetoWindows();
    }

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
            log.info("Lifecycle send-gate: id={} SAFETY_REJECTED — {}", id, verdict.details());
            return;
        }

        lifecycleQueueService.openVetoWindow(id);

        if (!TIER_AMBER.equalsIgnoreCase(row.getTier())) {
            // RED (reserved, P2/out-of-scope this increment): no auto-approve; left at
            // AWAITING_VETO_WINDOW for admin review or the step-D backlog safety net.
            return;
        }

        if (isOptedOut(row)) {
            lifecycleQueueService.veto(id, SUPPRESS_REASON_OPT_OUT);
            log.info("Lifecycle send-gate: id={} VETOED (opt-out at approve)", id);
            return;
        }

        lifecycleQueueService.approve(id);
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
            log.info("Lifecycle send-gate: id={} DEFERRED until={} (quiet hours)", id, nextWindowOpen);
            return;
        }

        if (isOptedOutValue(pref)) {
            // Mid-flight opt-out (post-approve, Edge-Case): never delivered.
            lifecycleQueueService.markSuppressed(id, SUPPRESS_REASON_OPT_OUT);
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
            log.info("Lifecycle send-gate: id={} VETOED (opt-out, expired-veto-window backlog)", id);
            return;
        }

        lifecycleQueueService.approve(id);
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
        NotificationType type = resolveType(row.getNotificationType());
        Map<String, Object> metadata = row.getMetadata();
        notificationService.deliver(
                row.getStudentId(), type, deriveTitle(type), row.getMessageBody(),
                metadata, extractActionUrl(metadata));
        lifecycleQueueService.markSent(row.getId());
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
        NotificationType type = resolveType(row.getNotificationType());
        return preferenceRepository.findByStudentIdAndNotificationType(row.getStudentId(), type);
    }

    static NotificationType resolveType(String raw) {
        try {
            return NotificationType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return NotificationType.GENERAL;
        }
    }
}
