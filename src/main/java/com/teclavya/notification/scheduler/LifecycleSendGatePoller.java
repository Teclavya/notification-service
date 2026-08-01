package com.teclavya.notification.scheduler;

import com.teclavya.notification.config.NsLifecycleFeatureFlags;
import com.teclavya.notification.entities.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@code lifecycle_message_review_queue} rows through the ethical send-gate state
 * machine — content-safety verify → AMBER auto-approve (design §2/§7, ADR-8/ADR-10).
 *
 * <p>Gated by {@link NsLifecycleFeatureFlags.SendGate#isEnabled()} (default false — AC-10.2):
 * when OFF, {@link #poll()} is a complete no-op and DRAFTED rows are left untouched.
 *
 * <p><b>Step A</b> — for each claimed DRAFTED row:
 * <ol>
 *   <li>{@code ContentSafetyVerifier#verify} (fail-closed, R4j-wrapped — never throws per
 *       contract, but the step defensively treats any unexpected exception as FAIL too).</li>
 *   <li>PASS → {@code applyVerdict(PASS)} [SAFETY_CHECKED] → {@code openVetoWindow()}
 *       [AWAITING_VETO_WINDOW].</li>
 *   <li>AMBER tier, veto window = 0 (ADR-10): opt-out re-checked immediately — opted-out
 *       (in-app disabled for this notification type) ⇒ {@code veto("opt-out")}, never
 *       approved; otherwise {@code approve()} [APPROVED] in the same pass, without waiting
 *       on {@code veto_window_expires_at}.</li>
 *   <li>RED tier (reserved, P2/out-of-scope): left at AWAITING_VETO_WINDOW for admin review
 *       or the expired-veto-window backlog safety net (step D).</li>
 *   <li>FAIL → {@code applyVerdict(FAIL)} [SAFETY_REJECTED] — terminal, never reachable from
 *       APPROVED/SENT.</li>
 * </ol>
 *
 * <p><b>Step B</b> — for each claimed APPROVED row: re-check quiet hours; if quiet,
 * {@code markDeferred(nextWindowOpen)} [DEFERRED]; otherwise re-check opt-out (mid-flight,
 * post-approve) — opted-out ⇒ {@code markSuppressed("opt-out")} [SUPPRESSED], never
 * delivered; otherwise deliver then {@code markSent()} [SENT, idempotent].
 *
 * <p><b>Step C</b> — for each claimed DEFERRED-due row (deferred_until elapsed): re-check
 * quiet hours + opt-out exactly as step B, then deliver → markSent. If still quiet (window
 * shifted again — rare edge case), the row is left DEFERRED and re-evaluated on the next
 * tick.
 *
 * <p><b>Step D</b> — backlog/RED safety net (design §7, ADR-10): for each claimed
 * AWAITING_VETO_WINDOW row whose veto window has expired, AMBER rows are auto-approved (with
 * the same opt-out-at-approve check as step A); RED rows (P2/out-of-scope) are left untouched.
 *
 * <p><b>Concurrency / transaction boundary (NS-BE-4 SEV-1 fix):</b> every claim query uses
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, and the row lock must be held for the whole
 * claim+process batch so a second concurrent poller instance's claim query simply skips
 * locked rows (design §8). {@code @Transactional} only engages through Spring's AOP proxy,
 * which is bypassed on a same-bean self-invocation — so the four claim+process steps live on
 * the SEPARATE {@link LifecycleSendGatePollerSteps} bean, and {@link #poll()} calls them
 * through that bean's injected proxy (a genuine cross-bean call) rather than via
 * {@code this.processDrafted()} etc. See {@link LifecycleSendGatePollerSteps}'s class javadoc
 * for the full rationale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LifecycleSendGatePoller {

    /** Bounded batch size per poller tick — cheap, indexed reads (design §13). */
    static final int BATCH_LIMIT = 50;

    private final NsLifecycleFeatureFlags featureFlags;
    private final LifecycleSendGatePollerSteps steps;

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        if (!featureFlags.getSendGate().isEnabled()) {
            // AC-10.2: flag OFF ⇒ zero verify/approve/send activity, rows stay wherever they are.
            return;
        }
        // Cross-bean calls (steps is a distinct @Component) — each genuinely goes through
        // Spring's transactional AOP proxy, unlike the self-invocation this replaced.
        steps.processDrafted();
        steps.processApprovedForSend();
        steps.processDueDeferrals();
        steps.processExpiredVetoWindows();
    }

    static NotificationType resolveType(String raw) {
        try {
            return NotificationType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return NotificationType.GENERAL;
        }
    }
}
