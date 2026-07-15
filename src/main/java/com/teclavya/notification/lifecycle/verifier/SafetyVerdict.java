package com.teclavya.notification.lifecycle.verifier;

/**
 * Immutable result of a content-safety check.
 *
 * <p>{@code pass=true} means the message cleared all rules; {@code pass=false} means at
 * least one rule failed or the verifier itself encountered an error (fail-closed).
 *
 * <p>The {@link #verdictString()} helper maps to the "PASS"/"FAIL" string stored in
 * {@code lifecycle_message_review_queue.safety_verdict} (as used by BE-2 / LifecycleQueueServiceImpl).
 */
public record SafetyVerdict(boolean pass, String details) {

    /** Canonical string for the DB column — matches the LifecycleQueueServiceImpl constants. */
    public String verdictString() {
        return pass ? "PASS" : "FAIL";
    }

    // ------------------------------------------------------------------
    // Static factories
    // ------------------------------------------------------------------

    public static SafetyVerdict pass(String details) {
        return new SafetyVerdict(true, details);
    }

    public static SafetyVerdict fail(String details) {
        return new SafetyVerdict(false, details);
    }
}
