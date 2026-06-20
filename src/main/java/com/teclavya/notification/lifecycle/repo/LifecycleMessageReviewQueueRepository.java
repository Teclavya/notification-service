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
}
