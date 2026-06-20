package com.teclavya.notification.lifecycle;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.impl.LifecycleQueueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LifecycleQueueServiceImpl — state machine transitions and guards.
 * Uses Mockito to stub the repository; no Spring context is loaded.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleQueueServiceTest {

    @Mock
    private LifecycleMessageReviewQueueRepository repository;

    private LifecycleQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LifecycleQueueServiceImpl(repository);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LifecycleMessageReviewQueue rowInState(LifecycleMessageStatus status) {
        return LifecycleMessageReviewQueue.builder()
                .id(UUID.randomUUID())
                .studentId("student-abc")
                .notificationType("INACTIVITY_REENGAGEMENT")
                .messageBody("Hello learner")
                .status(status)
                .tier("AMBER")
                .build();
    }

    private LifecycleMessageReviewQueue rowInStateWithVerdict(
            LifecycleMessageStatus status, String verdict) {
        LifecycleMessageReviewQueue r = rowInState(status);
        r.setSafetyVerdict(verdict);
        return r;
    }

    private void stubLoad(LifecycleMessageReviewQueue row) {
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        // lenient: guard-throwing tests exit before save is called
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // enqueue
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("enqueue")
    class EnqueueTests {

        @Test
        @DisplayName("creates row with DRAFTED status, extracts timezone and tier from metadata")
        void enqueue_setsStatusDraftedAndExtractsMetadata() {
            InternalSendRequest req = InternalSendRequest.builder()
                    .sourceService("lpt")
                    .studentId("student-1")
                    .notificationType("INACTIVITY_REENGAGEMENT")
                    .title("Come back!")
                    .body("You haven't learned today.")
                    .metadata(Map.of("timezone", "Asia/Kolkata", "tier", "AMBER"))
                    .build();

            when(repository.save(any())).thenAnswer(inv -> {
                LifecycleMessageReviewQueue r = inv.getArgument(0);
                r = LifecycleMessageReviewQueue.builder()
                        .id(UUID.randomUUID())
                        .studentId(r.getStudentId())
                        .notificationType(r.getNotificationType())
                        .messageBody(r.getMessageBody())
                        .status(r.getStatus())
                        .tier(r.getTier())
                        .timezone(r.getTimezone())
                        .metadata(r.getMetadata())
                        .build();
                return r;
            });

            LifecycleMessageReviewQueue result = service.enqueue(req);

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
            assertThat(result.getStudentId()).isEqualTo("student-1");
            assertThat(result.getTimezone()).isEqualTo("Asia/Kolkata");
            assertThat(result.getTier()).isEqualTo("AMBER");
            verify(repository).save(any());
        }

        @Test
        @DisplayName("timezone falls back to null when metadata is null")
        void enqueue_timezoneNullWhenMetadataAbsent() {
            InternalSendRequest req = InternalSendRequest.builder()
                    .sourceService("lpt")
                    .studentId("student-2")
                    .notificationType("INACTIVITY_REENGAGEMENT")
                    .title("Come back!")
                    .body("Miss you")
                    .metadata(null)
                    .build();

            ArgumentCaptor<LifecycleMessageReviewQueue> captor =
                    ArgumentCaptor.forClass(LifecycleMessageReviewQueue.class);
            when(repository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            service.enqueue(req);

            LifecycleMessageReviewQueue saved = captor.getValue();
            assertThat(saved.getTimezone()).isNull();
            assertThat(saved.getTier()).isEqualTo("AMBER");
        }
    }

    // -----------------------------------------------------------------------
    // applyVerdict
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("applyVerdict")
    class ApplyVerdictTests {

        @Test
        @DisplayName("PASS: sets SAFETY_CHECKED (not AWAITING_VETO_WINDOW) + PASS verdict + veto_window_expires_at ~5m from now")
        void applyVerdict_pass_setsSafetyChecked() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            Instant before = Instant.now();
            LifecycleMessageReviewQueue result = service.applyVerdict(row.getId(), true, "ok");
            Instant after = Instant.now();

            // AC-2.2 / AC-3.2 / GOLDEN-01 step 2: SAFETY_CHECKED must be a real persisted state
            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SAFETY_CHECKED);
            assertThat(result.getSafetyVerdict()).isEqualTo("PASS");
            assertThat(result.getVetoWindowExpiresAt())
                    .isAfter(before.plus(4, ChronoUnit.MINUTES))
                    .isBefore(after.plus(6, ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("FAIL: sets SAFETY_REJECTED + FAIL verdict + clears veto window")
        void applyVerdict_fail_setsSafetyRejected() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.applyVerdict(row.getId(), false, "toxic content");

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SAFETY_REJECTED);
            assertThat(result.getSafetyVerdict()).isEqualTo("FAIL");
            assertThat(result.getVetoWindowExpiresAt()).isNull();
            assertThat(result.getSafetyDetails()).isEqualTo("toxic content");
        }
    }

    // -----------------------------------------------------------------------
    // openVetoWindow
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("openVetoWindow")
    class OpenVetoWindowTests {

        @Test
        @DisplayName("legal: SAFETY_CHECKED + PASS → AWAITING_VETO_WINDOW")
        void openVetoWindow_fromSafetyChecked_setsAwaitingVetoWindow() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_CHECKED, "PASS");
            row.setVetoWindowExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.openVetoWindow(row.getId());

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
        }

        @Test
        @DisplayName("illegal: DRAFTED → openVetoWindow throws")
        void openVetoWindow_fromDrafted_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.openVetoWindow(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFTED");
        }

        @Test
        @DisplayName("illegal: AWAITING_VETO_WINDOW → openVetoWindow throws (already past SAFETY_CHECKED)")
        void openVetoWindow_fromAwaitingVetoWindow_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.openVetoWindow(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AWAITING_VETO_WINDOW");
        }

        @Test
        @DisplayName("illegal: SAFETY_REJECTED → openVetoWindow throws")
        void openVetoWindow_fromSafetyRejected_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_REJECTED, "FAIL");
            stubLoad(row);

            assertThatThrownBy(() -> service.openVetoWindow(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SAFETY_REJECTED");
        }

        @Test
        @DisplayName("illegal: APPROVED → openVetoWindow throws")
        void openVetoWindow_fromApproved_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.openVetoWindow(row.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("verify flow: applyVerdict(PASS) then openVetoWindow yields AWAITING_VETO_WINDOW — two distinct saves")
        void verifyFlow_twoWritesProduceSafetyCheckedThenAwaitingVetoWindow() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            // First write: applyVerdict → SAFETY_CHECKED
            LifecycleMessageReviewQueue afterVerdict = service.applyVerdict(row.getId(), true, "clean");
            assertThat(afterVerdict.getStatus()).isEqualTo(LifecycleMessageStatus.SAFETY_CHECKED);

            // Second write: openVetoWindow → AWAITING_VETO_WINDOW
            LifecycleMessageReviewQueue afterOpen = service.openVetoWindow(row.getId());
            assertThat(afterOpen.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);

            // repository.save must be called twice (once per write)
            verify(repository, times(2)).save(any());
        }
    }

    // -----------------------------------------------------------------------
    // approve
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("approve")
    class ApproveTests {

        @Test
        @DisplayName("legal: AWAITING_VETO_WINDOW + PASS → APPROVED")
        void approve_fromAwaitingWithPass_setsApproved() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.approve(row.getId());

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
        }

        @Test
        @DisplayName("legal: SAFETY_CHECKED + PASS → APPROVED (AC-2.4 early admin approval)")
        void approve_fromSafetyCheckedWithPass_setsApproved() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_CHECKED, "PASS");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.approve(row.getId());

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
        }

        @Test
        @DisplayName("illegal: DRAFTED → approve throws IllegalStateException")
        void approve_fromDrafted_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFTED");
        }

        @Test
        @DisplayName("illegal: SAFETY_REJECTED → approve throws IllegalStateException")
        void approve_fromSafetyRejected_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_REJECTED, "FAIL");
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SAFETY_REJECTED");
        }

        @Test
        @DisplayName("illegal: AWAITING_VETO_WINDOW + FAIL verdict → approve throws (safety invariant)")
        void approve_withFailVerdict_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "FAIL");
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAIL");
        }

        @Test
        @DisplayName("illegal: SAFETY_CHECKED + FAIL verdict → approve throws (safety invariant — no bypass)")
        void approve_fromSafetyCheckedWithFailVerdict_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_CHECKED, "FAIL");
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAIL");
        }

        @Test
        @DisplayName("illegal: already APPROVED → approve throws (not SAFETY_CHECKED or AWAITING_VETO_WINDOW)")
        void approve_fromApproved_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("no-bypass invariant: DRAFTED + null verdict cannot reach APPROVED via approve")
        void approve_noBypass_draftedCannotReachApproved() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.approve(row.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // -----------------------------------------------------------------------
    // veto
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("veto")
    class VetoTests {

        @Test
        @DisplayName("legal: AWAITING_VETO_WINDOW → VETOED")
        void veto_fromAwaiting_setsVetoed() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.veto(row.getId(), "offensive");

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.VETOED);
            assertThat(result.getVetoReason()).isEqualTo("offensive");
        }

        @Test
        @DisplayName("legal: SAFETY_REJECTED → VETOED")
        void veto_fromSafetyRejected_setsVetoed() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_REJECTED, "FAIL");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.veto(row.getId(), "admin decision");

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.VETOED);
        }

        @Test
        @DisplayName("illegal: DRAFTED → veto throws")
        void veto_fromDrafted_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.veto(row.getId(), "reason"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFTED");
        }

        @Test
        @DisplayName("illegal: APPROVED → veto throws")
        void veto_fromApproved_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.veto(row.getId(), "reason"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("illegal: SENT → veto throws")
        void veto_fromSent_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SENT, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.veto(row.getId(), "reason"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // -----------------------------------------------------------------------
    // editBody
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("editBody")
    class EditBodyTests {

        @Test
        @DisplayName("legal: SAFETY_CHECKED → body updated, reset to DRAFTED, veto window cleared, verdict cleared")
        void editBody_fromSafetyChecked_resetsToDrafted() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_CHECKED, "PASS");
            row.setVetoWindowExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.editBody(row.getId(), "Revised body");

            assertThat(result.getMessageBody()).isEqualTo("Revised body");
            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
            assertThat(result.getSafetyVerdict()).isNull();
            assertThat(result.getVetoWindowExpiresAt()).isNull();
        }

        @Test
        @DisplayName("legal: AWAITING_VETO_WINDOW → body updated, reset to DRAFTED, veto window cleared")
        void editBody_fromAwaiting_resetsToDrafted() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS");
            row.setVetoWindowExpiresAt(Instant.now().plus(3, ChronoUnit.MINUTES));
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.editBody(row.getId(), "Updated body");

            assertThat(result.getMessageBody()).isEqualTo("Updated body");
            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
            assertThat(result.getSafetyVerdict()).isNull();
            assertThat(result.getVetoWindowExpiresAt()).isNull();
        }

        @Test
        @DisplayName("legal: SAFETY_REJECTED → body updated, reset to DRAFTED")
        void editBody_fromSafetyRejected_resetsToDrafted() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SAFETY_REJECTED, "FAIL");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.editBody(row.getId(), "Corrected body");

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
            assertThat(result.getSafetyVerdict()).isNull();
        }

        @Test
        @DisplayName("illegal: APPROVED → editBody throws")
        void editBody_fromApproved_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.editBody(row.getId(), "new body"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("illegal: SENT → editBody throws")
        void editBody_fromSent_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SENT, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.editBody(row.getId(), "new body"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // -----------------------------------------------------------------------
    // markSent
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("markSent")
    class MarkSentTests {

        @Test
        @DisplayName("legal: APPROVED + PASS → SENT, sentAt populated")
        void markSent_fromApproved_setsSent() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            Instant before = Instant.now();
            LifecycleMessageReviewQueue result = service.markSent(row.getId());
            Instant after = Instant.now();

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
            assertThat(result.getSentAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("idempotent: already SENT → no-op (no second save)")
        void markSent_alreadySent_noOp() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.SENT, "PASS");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.markSent(row.getId());

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
            // save must NOT be called for the idempotent path
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("illegal: DRAFTED → markSent throws")
        void markSent_fromDrafted_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.markSent(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFTED");
        }

        @Test
        @DisplayName("illegal: AWAITING_VETO_WINDOW → markSent throws")
        void markSent_fromAwaiting_throws() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS");
            stubLoad(row);

            assertThatThrownBy(() -> service.markSent(row.getId()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("safety invariant: APPROVED with null verdict → markSent throws")
        void markSent_withNullVerdict_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.APPROVED);
            // verdict deliberately null to simulate a bug
            row.setSafetyVerdict(null);
            stubLoad(row);

            assertThatThrownBy(() -> service.markSent(row.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Safety invariant");
        }

        @Test
        @DisplayName("legal: DEFERRED + PASS → SENT")
        void markSent_fromDeferred_setsSent() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.DEFERRED, "PASS");
            stubLoad(row);

            LifecycleMessageReviewQueue result = service.markSent(row.getId());

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
        }
    }

    // -----------------------------------------------------------------------
    // markDeferred
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("markDeferred")
    class MarkDeferredTests {

        @Test
        @DisplayName("legal: APPROVED → DEFERRED, deferredUntil set")
        void markDeferred_fromApproved_setsDeferred() {
            LifecycleMessageReviewQueue row = rowInStateWithVerdict(
                    LifecycleMessageStatus.APPROVED, "PASS");
            stubLoad(row);

            Instant until = Instant.now().plus(8, ChronoUnit.HOURS);
            LifecycleMessageReviewQueue result = service.markDeferred(row.getId(), until);

            assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DEFERRED);
            assertThat(result.getDeferredUntil()).isEqualTo(until);
        }

        @Test
        @DisplayName("illegal: DRAFTED → markDeferred throws")
        void markDeferred_fromDrafted_throws() {
            LifecycleMessageReviewQueue row = rowInState(LifecycleMessageStatus.DRAFTED);
            stubLoad(row);

            assertThatThrownBy(() -> service.markDeferred(row.getId(), Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFTED");
        }
    }

    // -----------------------------------------------------------------------
    // findExpiredVetoWindows — delegates to repository with correct arguments
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("findExpiredVetoWindows")
    class FindExpiredVetoWindowsTests {

        @Test
        @DisplayName("returns only AWAITING_VETO_WINDOW rows past expiry from repository")
        void findExpiredVetoWindows_delegatesCorrectly() {
            LifecycleMessageReviewQueue expired = rowInStateWithVerdict(
                    LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS");
            expired.setVetoWindowExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));

            when(repository.findByStatusAndVetoWindowExpiresAtBefore(
                    eq(LifecycleMessageStatus.AWAITING_VETO_WINDOW), any(Instant.class)))
                    .thenReturn(List.of(expired));

            List<LifecycleMessageReviewQueue> results = service.findExpiredVetoWindows();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
            verify(repository).findByStatusAndVetoWindowExpiresAtBefore(
                    eq(LifecycleMessageStatus.AWAITING_VETO_WINDOW), any(Instant.class));
        }

        @Test
        @DisplayName("returns empty list when no expired rows")
        void findExpiredVetoWindows_returnsEmpty() {
            when(repository.findByStatusAndVetoWindowExpiresAtBefore(any(), any()))
                    .thenReturn(List.of());

            assertThat(service.findExpiredVetoWindows()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // findDueDeferrals
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findDueDeferrals delegates to repository with DEFERRED status")
    void findDueDeferrals_delegatesCorrectly() {
        when(repository.findByStatusAndDeferredUntilBefore(
                eq(LifecycleMessageStatus.DEFERRED), any(Instant.class)))
                .thenReturn(List.of());

        service.findDueDeferrals();

        verify(repository).findByStatusAndDeferredUntilBefore(
                eq(LifecycleMessageStatus.DEFERRED), any(Instant.class));
    }

    // -----------------------------------------------------------------------
    // findDueForSend
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findDueForSend returns APPROVED rows")
    void findDueForSend_returnsApprovedRows() {
        LifecycleMessageReviewQueue approved = rowInStateWithVerdict(
                LifecycleMessageStatus.APPROVED, "PASS");
        when(repository.findByStatus(LifecycleMessageStatus.APPROVED))
                .thenReturn(List.of(approved));

        List<LifecycleMessageReviewQueue> results = service.findDueForSend();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
    }
}
