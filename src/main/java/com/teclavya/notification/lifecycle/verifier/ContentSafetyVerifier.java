package com.teclavya.notification.lifecycle.verifier;

/**
 * Contract for content-safety verification of lifecycle notification bodies.
 *
 * <p>Implementations MUST be fail-closed: any internal error or timeout must
 * return {@link SafetyVerdict#fail(String)} rather than throwing or returning PASS.
 */
public interface ContentSafetyVerifier {

    /**
     * Verify the given message body for the supplied notification type.
     *
     * @param messageBody      the rendered text body of the notification
     * @param notificationType the type string (e.g. "INACTIVITY_REENGAGEMENT", "EMAIL_*")
     * @return a {@link SafetyVerdict} — never {@code null}, never PASS on error
     */
    SafetyVerdict verify(String messageBody, String notificationType);
}
