package com.teclavya.notification.lifecycle.repo;

import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface LifecycleMessageReviewQueueRepository
        extends JpaRepository<LifecycleMessageReviewQueue, UUID> {

    /**
     * Poller: rows whose veto window has expired and are still AWAITING_VETO_WINDOW.
     */
    List<LifecycleMessageReviewQueue> findByStatusAndVetoWindowExpiresAtBefore(
            LifecycleMessageStatus status, Instant threshold);

    /**
     * Find all rows with a given status — used by findDueForSend (APPROVED rows).
     */
    List<LifecycleMessageReviewQueue> findByStatus(LifecycleMessageStatus status);

    /**
     * Poller: deferred rows whose defer window has elapsed.
     */
    List<LifecycleMessageReviewQueue> findByStatusAndDeferredUntilBefore(
            LifecycleMessageStatus status, Instant threshold);

    /**
     * Admin list: paginated, filtered by status, newest-first.
     */
    @Query("SELECT q FROM LifecycleMessageReviewQueue q WHERE q.status = :status ORDER BY q.createdAt DESC")
    Page<LifecycleMessageReviewQueue> findByStatusOrderByCreatedAtDesc(
            @Param("status") LifecycleMessageStatus status, Pageable pageable);

    /**
     * LifecycleSendGatePoller step A (NS-BE-4a): claims up to {@code limit} DRAFTED rows for
     * content-safety verification. {@code FOR UPDATE SKIP LOCKED} means two concurrent poller
     * instances never claim the same row (design §8, defence-in-depth for prod — staging is
     * single-instance). Must be called from within an active transaction so the row lock is
     * held for the duration of the caller's verify+approve work, not just this query.
     */
    @Query(value = "SELECT * FROM lifecycle_message_review_queue "
            + "WHERE status = 'DRAFTED' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<LifecycleMessageReviewQueue> claimDraftedBatch(@Param("limit") int limit);

    /**
     * LifecycleSendGatePoller step B (NS-BE-4b): claims up to {@code limit} APPROVED rows for
     * the quiet-hours check + delivery step. SKIP LOCKED — see {@link #claimDraftedBatch(int)}.
     */
    @Query(value = "SELECT * FROM lifecycle_message_review_queue "
            + "WHERE status = 'APPROVED' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<LifecycleMessageReviewQueue> claimApprovedBatch(@Param("limit") int limit);

    /**
     * LifecycleSendGatePoller step C (NS-BE-4b): claims up to {@code limit} DEFERRED rows whose
     * {@code deferred_until} has elapsed (quiet-hours drain). SKIP LOCKED — see
     * {@link #claimDraftedBatch(int)}.
     */
    @Query(value = "SELECT * FROM lifecycle_message_review_queue "
            + "WHERE status = 'DEFERRED' AND deferred_until < :threshold "
            + "ORDER BY deferred_until ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<LifecycleMessageReviewQueue> claimDueDeferralsBatch(
            @Param("threshold") Instant threshold, @Param("limit") int limit);

    /**
     * LifecycleSendGatePoller step D (NS-BE-4b): claims up to {@code limit} AWAITING_VETO_WINDOW
     * rows whose veto window has expired — backlog/RED safety net (design §7, ADR-10). SKIP
     * LOCKED — see {@link #claimDraftedBatch(int)}.
     */
    @Query(value = "SELECT * FROM lifecycle_message_review_queue "
            + "WHERE status = 'AWAITING_VETO_WINDOW' AND veto_window_expires_at < :threshold "
            + "ORDER BY veto_window_expires_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<LifecycleMessageReviewQueue> claimExpiredVetoWindowBatch(
            @Param("threshold") Instant threshold, @Param("limit") int limit);
}
