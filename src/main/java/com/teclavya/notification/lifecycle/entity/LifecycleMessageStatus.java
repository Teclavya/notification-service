package com.teclavya.notification.lifecycle.entity;

/**
 * State machine values for lifecycle_message_review_queue.status.
 *
 * Legal transitions (P1 AMBER path):
 *   DRAFTED → AWAITING_VETO_WINDOW  (applyVerdict PASS)
 *   DRAFTED → SAFETY_REJECTED       (applyVerdict FAIL)
 *   AWAITING_VETO_WINDOW → APPROVED (approve or poller auto-advance)
 *   AWAITING_VETO_WINDOW → VETOED   (veto)
 *   APPROVED → SENT                 (markSent)
 *   APPROVED → DEFERRED             (markDeferred — quiet hours)
 *   DEFERRED → APPROVED             (poller after deferred_until elapses)
 *   SAFETY_REJECTED → VETOED        (veto)
 *   AWAITING_VETO_WINDOW → DRAFTED  (editBody — triggers re-verify)
 *   SAFETY_REJECTED → DRAFTED       (editBody — triggers re-verify)
 *
 * SAFETY_CHECKED is reserved as a transient internal step documented in the
 * spec but collapsed into AWAITING_VETO_WINDOW in the single applyVerdict call.
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
