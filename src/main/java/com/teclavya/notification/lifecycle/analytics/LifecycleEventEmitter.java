package com.teclavya.notification.lifecycle.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Non-fatal event emitter for lifecycle analytics.
 *
 * <p>Persists a {@link LifecycleEvent} row for every significant lifecycle action.
 * Any repository failure is caught and logged — the emitter NEVER propagates an
 * exception to its caller (non-fatal guarantee).
 *
 * <h3>Canonical event type constants</h3>
 * <ul>
 *   <li>{@link #EVT_SEND_BLOCKED} — lifecycle_message.send_blocked</li>
 *   <li>{@link #EVT_SAFETY_CHECKED} — lifecycle_message.safety_checked</li>
 *   <li>{@link #EVT_SAFETY_REJECTED} — lifecycle_message.safety_rejected</li>
 *   <li>{@link #EVT_APPROVED} — lifecycle_message.approved</li>
 *   <li>{@link #EVT_VETOED} — lifecycle_message.vetoed</li>
 *   <li>{@link #EVT_SENT} — lifecycle_message.sent</li>
 *   <li>{@link #EVT_DEFERRED} — lifecycle_message.deferred</li>
 *   <li>{@link #EVT_FEATURE_FLAG_UPDATED} — feature_flag.updated</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LifecycleEventEmitter {

    // -----------------------------------------------------------------------
    // Canonical event types (spec: §16 analytics table)
    // -----------------------------------------------------------------------

    public static final String EVT_SEND_BLOCKED          = "lifecycle_message.send_blocked";
    public static final String EVT_SAFETY_CHECKED        = "lifecycle_message.safety_checked";
    public static final String EVT_SAFETY_REJECTED       = "lifecycle_message.safety_rejected";
    public static final String EVT_APPROVED              = "lifecycle_message.approved";
    public static final String EVT_VETOED                = "lifecycle_message.vetoed";
    public static final String EVT_SENT                  = "lifecycle_message.sent";
    public static final String EVT_DEFERRED              = "lifecycle_message.deferred";
    public static final String EVT_FEATURE_FLAG_UPDATED  = "feature_flag.updated";

    // -----------------------------------------------------------------------

    private final LifecycleEventRepository repository;

    // -----------------------------------------------------------------------
    // Core emit
    // -----------------------------------------------------------------------

    /**
     * Persist a lifecycle analytics event.
     *
     * <p>This method is intentionally non-fatal: any {@link Exception} thrown by the
     * repository is caught and logged at WARN level. Callers must NOT rely on this
     * completing successfully — it is best-effort instrumentation.
     *
     * @param eventType  canonical event type string (use the {@code EVT_*} constants)
     * @param studentId  the student this event concerns
     * @param messageId  optional reference to a lifecycle_message_review_queue row
     * @param properties arbitrary additional context (may be {@code null})
     */
    public void emit(String eventType, String studentId, UUID messageId,
                     Map<String, Object> properties) {
        try {
            LifecycleEvent event = LifecycleEvent.builder()
                    .eventType(eventType)
                    .studentId(studentId)
                    .messageId(messageId)
                    .properties(properties)
                    .build();
            repository.save(event);
            log.debug("LifecycleEvent emitted type={} studentId={} messageId={}",
                    eventType, studentId, messageId);
        } catch (Exception ex) {
            // NON-FATAL — log and swallow; analytics must never break the main flow.
            log.warn("LifecycleEventEmitter: failed to persist event type={} studentId={} — {}",
                    eventType, studentId, ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    // Convenience methods for canonical event types
    // -----------------------------------------------------------------------

    /** Message was blocked from sending (safety or veto). */
    public void emitSendBlocked(String studentId, UUID messageId, String reason) {
        emit(EVT_SEND_BLOCKED, studentId, messageId,
                Map.of("reason", reason != null ? reason : ""));
    }

    /** Message passed safety check. */
    public void emitSafetyChecked(String studentId, UUID messageId) {
        emit(EVT_SAFETY_CHECKED, studentId, messageId, null);
    }

    /** Message failed safety check. */
    public void emitSafetyRejected(String studentId, UUID messageId, String details) {
        emit(EVT_SAFETY_REJECTED, studentId, messageId,
                Map.of("details", details != null ? details : ""));
    }

    /** Message was approved for sending. */
    public void emitApproved(String studentId, UUID messageId) {
        emit(EVT_APPROVED, studentId, messageId, null);
    }

    /** Message was vetoed. */
    public void emitVetoed(String studentId, UUID messageId, String reason) {
        emit(EVT_VETOED, studentId, messageId,
                Map.of("reason", reason != null ? reason : ""));
    }

    /** Message was sent. */
    public void emitSent(String studentId, UUID messageId) {
        emit(EVT_SENT, studentId, messageId, null);
    }

    /** Message was deferred. */
    public void emitDeferred(String studentId, UUID messageId, String deferredUntil) {
        emit(EVT_DEFERRED, studentId, messageId,
                Map.of("deferredUntil", deferredUntil != null ? deferredUntil : ""));
    }

    /** A feature flag was toggled. */
    public void emitFeatureFlagUpdated(String studentId, String flagName, boolean newValue) {
        emit(EVT_FEATURE_FLAG_UPDATED, studentId, null,
                Map.of("flagName", flagName != null ? flagName : "",
                       "newValue", newValue));
    }
}
