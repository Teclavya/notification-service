package com.teclavya.notification.lifecycle.entity;

/**
 * State machine values for lifecycle_message_review_queue.status.
 *
 * Legal transitions (P1 AMBER path):
 *   DRAFTED → SAFETY_CHECKED        (applyVerdict PASS — persists as distinct row)
 *   DRAFTED → SAFETY_REJECTED       (applyVerdict FAIL)
 *   SAFETY_CHECKED → AWAITING_VETO_WINDOW (openVetoWindow — called immediately after applyVerdict PASS)
 *   AWAITING_VETO_WINDOW → APPROVED (approve or poller auto-advance)
 *   SAFETY_CHECKED → APPROVED       (approve — admin early-approval per AC-2.4)
 *   AWAITING_VETO_WINDOW → VETOED   (veto)
 *   APPROVED → SENT                 (markSent)
 *   APPROVED → DEFERRED             (markDeferred — quiet hours)
 *   DEFERRED → APPROVED             (poller after deferred_until elapses)
 *   SAFETY_REJECTED → VETOED        (veto)
 *   AWAITING_VETO_WINDOW → DRAFTED  (editBody — triggers re-verify)
 *   SAFETY_CHECKED → DRAFTED        (editBody — triggers re-verify)
 *   SAFETY_REJECTED → DRAFTED       (editBody — triggers re-verify)
 *
 * SAFETY_CHECKED is a real, persisted state (AC-2.2, AC-3.2, AC-5.1, GOLDEN-01 step 2).
 * The verify flow persists SAFETY_CHECKED first, then immediately transitions to
 * AWAITING_VETO_WINDOW via openVetoWindow, giving the audit/event trail a real
 * SAFETY_CHECKED row.
 */
public enum LifecycleMessageStatus {
    DRAFTED,
    SAFETY_CHECKED,
    AWAITING_VETO_WINDOW,
    APPROVED,
    DEFERRED,
    SENT,
    VETOED,
    SAFETY_REJECTED
}
