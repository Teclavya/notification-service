package com.teclavya.notification.lifecycle.service;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * State-machine service for lifecycle_message_review_queue.
 *
 * Safety invariant: status=APPROVED or SENT is only reachable when safety_verdict=PASS.
 * Every state-changing method is @Transactional in the implementation.
 */
public interface LifecycleQueueService {

    /**
     * Creates a new queue row with status=DRAFTED from an internal send request.
     * Extracts timezone and tier from req.metadata; falls back to null / "AMBER".
     */
    LifecycleMessageReviewQueue enqueue(InternalSendRequest req);

    /**
     * Records the content-safety verdict.
     *
     * PASS path: status → AWAITING_VETO_WINDOW, safety_verdict=PASS,
     *            veto_window_expires_at = now + 5 minutes.
     * FAIL path: status → SAFETY_REJECTED, safety_verdict=FAIL.
     *
     * @param pass    true = PASS, false = FAIL
     * @param details free-text detail from the verifier (stored in safety_details)
     */
    LifecycleMessageReviewQueue applyVerdict(UUID id, boolean pass, String details);

    /**
     * Admin or poller approves the message for sending.
     * Guard: only allowed from AWAITING_VETO_WINDOW with safety_verdict=PASS.
     * Throws IllegalStateException otherwise (maps to HTTP 409).
     */
    LifecycleMessageReviewQueue approve(UUID id);

    /**
     * Admin vetoes the message (blocks send).
     * Guard: only allowed from AWAITING_VETO_WINDOW or SAFETY_REJECTED.
     * Throws IllegalStateException otherwise.
     */
    LifecycleMessageReviewQueue veto(UUID id, String reason);

    /**
     * Admin edits the message body and resets to DRAFTED for re-verification.
     * Clears veto_window_expires_at.
     * Guard: only allowed from AWAITING_VETO_WINDOW or SAFETY_REJECTED.
     */
    LifecycleMessageReviewQueue editBody(UUID id, String body);

    /**
     * Records that the message was sent.
     * Idempotent — no-op if already SENT.
     * Guard: only from APPROVED or DEFERRED; otherwise IllegalStateException.
     */
    LifecycleMessageReviewQueue markSent(UUID id);

    /**
     * Defers sending (e.g. quiet hours enforcement).
     * Sets status=DEFERRED and deferred_until.
     * Guard: only from APPROVED; otherwise IllegalStateException.
     */
    LifecycleMessageReviewQueue markDeferred(UUID id, Instant deferredUntil);

    /** Returns all APPROVED rows — feed for the send poller. */
    List<LifecycleMessageReviewQueue> findDueForSend();

    /** Returns AWAITING_VETO_WINDOW rows whose veto_window_expires_at is in the past. */
    List<LifecycleMessageReviewQueue> findExpiredVetoWindows();

    /** Returns DEFERRED rows whose deferred_until is in the past. */
    List<LifecycleMessageReviewQueue> findDueDeferrals();

    /** Paginated admin list filtered by status, newest-first. */
    Page<LifecycleMessageReviewQueue> listForAdmin(LifecycleMessageStatus status, Pageable pageable);
}
