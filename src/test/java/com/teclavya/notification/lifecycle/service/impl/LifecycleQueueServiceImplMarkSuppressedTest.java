package com.teclavya.notification.lifecycle.service.impl;

import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * State-machine unit tests for {@link LifecycleQueueServiceImpl#markSuppressed} (NS-BE-2).
 *
 * <p>SUPPRESSED is a terminal state distinct from SENT — a suppressed row's {@code status}
 * field is SUPPRESSED, never SENT, so any query/metric that counts "messages actually sent"
 * by filtering on {@code status = SENT} (e.g. {@link LifecycleQueueServiceImpl#findDueForSend()}
 * for APPROVED, or an equivalent SENT-count query) structurally excludes SUPPRESSED rows.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleQueueServiceImplMarkSuppressedTest {

    @Mock
    private LifecycleMessageReviewQueueRepository repository;

    private LifecycleQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LifecycleQueueServiceImpl(repository);
    }

    private LifecycleMessageReviewQueue rowWithStatus(UUID id, LifecycleMessageStatus status) {
        return LifecycleMessageReviewQueue.builder()
                .id(id)
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("body")
                .status(status)
                .safetyVerdict("PASS")
                .build();
    }

    // -----------------------------------------------------------------------
    // Legal transitions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("APPROVED -> SUPPRESSED is a legal transition")
    void approvedToSuppressed_legal() {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = rowWithStatus(id, LifecycleMessageStatus.APPROVED);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LifecycleMessageReviewQueue result = service.markSuppressed(id, "quiet hours");

        assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SUPPRESSED);
        assertThat(result.getVetoReason()).isEqualTo("quiet hours");
    }

    @Test
    @DisplayName("DEFERRED -> SUPPRESSED is a legal transition")
    void deferredToSuppressed_legal() {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = rowWithStatus(id, LifecycleMessageStatus.DEFERRED);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LifecycleMessageReviewQueue result = service.markSuppressed(id, "student muted milestone reminders");

        assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SUPPRESSED);
    }

    // -----------------------------------------------------------------------
    // Illegal transitions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DRAFTED -> SUPPRESSED is rejected (IllegalStateException)")
    void draftedToSuppressed_rejected() {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = rowWithStatus(id, LifecycleMessageStatus.DRAFTED);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.markSuppressed(id, "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SENT -> SUPPRESSED is rejected (terminal state, no path back)")
    void sentToSuppressed_rejected() {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = rowWithStatus(id, LifecycleMessageStatus.SENT);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.markSuppressed(id, "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // SUPPRESSED must never be reachable from / counted as SENT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("A suppressed row's status is SUPPRESSED, not SENT — findDueForSend(APPROVED) " +
            "and any status=SENT query structurally exclude it")
    void suppressedRow_neverCountedAsSent() {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = rowWithStatus(id, LifecycleMessageStatus.APPROVED);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LifecycleMessageReviewQueue result = service.markSuppressed(id, "quiet hours");

        assertThat(result.getStatus())
                .isNotEqualTo(LifecycleMessageStatus.SENT)
                .isEqualTo(LifecycleMessageStatus.SUPPRESSED);

        // markSent() must also refuse to move a SUPPRESSED row to SENT (guard only allows
        // APPROVED/DEFERRED as source states — SUPPRESSED is excluded by construction).
        assertThatThrownBy(() -> service.markSent(id))
                .isInstanceOf(IllegalStateException.class);
    }
}
