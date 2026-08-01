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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
 * <p>Steps B (send/defer), C (deferral drain), and D (expired-veto-window backlog) are added
 * by NS-BE-4b.
 *
 * <p>Concurrency: {@link LifecycleMessageReviewQueueRepository#claimDraftedBatch(int)} uses
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, and {@link #processDrafted()} is
 * {@code @Transactional} so the row locks are held for the whole claim+verify+approve batch —
 * a second concurrent poller instance's claim query simply skips locked rows (design §8).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleSendGatePoller {

    /** Bounded batch size per poller tick — cheap, indexed reads (design §13). */
    static final int BATCH_LIMIT = 50;

    private static final String TIER_AMBER = "AMBER";
    private static final String SUPPRESS_REASON_OPT_OUT = "opt-out";

    private final LifecycleMessageReviewQueueRepository repository;
    private final LifecycleQueueService lifecycleQueueService;
    private final ContentSafetyVerifier contentSafetyVerifier;
    private final NsLifecycleFeatureFlags featureFlags;
    private final NotificationPreferenceRepository preferenceRepository;

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        if (!featureFlags.getSendGate().isEnabled()) {
            // AC-10.2: flag OFF ⇒ zero verify/approve activity, rows stay DRAFTED.
            return;
        }
        processDrafted();
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
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Opt-out = a persisted {@link NotificationPreference} row for this student+type with
     * {@code inAppEnabled=false}. No persisted row ⇒ default-enabled ⇒ not opted out (mirrors
     * {@code @Builder.Default inAppEnabled=true} and the "never silently block" posture used by
     * {@link com.teclavya.notification.service.QuietHoursEvaluator} for quiet hours).
     */
    boolean isOptedOut(LifecycleMessageReviewQueue row) {
        return loadPreference(row)
                .map(pref -> !pref.isInAppEnabled())
                .orElse(false);
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
