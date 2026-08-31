package com.teclavya.notification.scheduler;

import com.teclavya.notification.config.NsLifecycleFeatureFlags;
import com.teclavya.notification.entities.NotificationPreference;
import com.teclavya.notification.entities.NotificationType;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.impl.LifecycleQueueServiceImpl;
import com.teclavya.notification.lifecycle.verifier.ContentSafetyVerifier;
import com.teclavya.notification.lifecycle.verifier.SafetyVerdict;
import com.teclavya.notification.repo.NotificationPreferenceRepository;
import com.teclavya.notification.service.NotificationService;
import com.teclavya.notification.service.QuietHoursEvaluator;
import com.teclavya.notification.lifecycle.analytics.LifecycleEventEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests (NS-BE-4a/NS-BE-4b) for {@link LifecycleSendGatePoller} — step A
 * (verify → approve, NS-BE-4a) and steps B/C/D (send/defer, deferral drain, expired-veto-window
 * backlog, NS-BE-4b) — against a real (H2/PostgreSQL-mode) database — same {@code @DataJpaTest}
 * infra as {@link com.teclavya.notification.lifecycle.LifecycleMessageReviewQueueRepositoryTest}
 * and {@link com.teclavya.notification.lifecycle.GatedSendEnqueueIntegrationTest}, per this
 * repo's existing convention.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:pollertestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=com.teclavya.notification.config.H2JsonbDialect",
        "spring.jpa.properties.hibernate.dialect=com.teclavya.notification.config.H2JsonbDialect",
        "spring.flyway.enabled=false"
})
class LifecycleSendGatePollerTest {

    @Autowired
    private LifecycleMessageReviewQueueRepository repository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    private ContentSafetyVerifier verifier;
    private NotificationService notificationService;
    private LifecycleEventEmitter lifecycleEventEmitter;
    private NsLifecycleFeatureFlags flags;
    private LifecycleSendGatePollerSteps steps;
    private LifecycleSendGatePoller poller;

    @BeforeEach
    void setUp() {
        verifier = mock(ContentSafetyVerifier.class);
        flags = new NsLifecycleFeatureFlags();
        flags.getSendGate().setEnabled(true);
        notificationService = mock(NotificationService.class);
        lifecycleEventEmitter = mock(LifecycleEventEmitter.class);
        steps = new LifecycleSendGatePollerSteps(
                repository, new LifecycleQueueServiceImpl(repository), verifier,
                preferenceRepository, new QuietHoursEvaluator(), notificationService, lifecycleEventEmitter);
        poller = new LifecycleSendGatePoller(flags, steps);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LifecycleMessageReviewQueue draftedRow(String tier) {
        return repository.save(LifecycleMessageReviewQueue.builder()
                .studentId("student-" + UUID.randomUUID())
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Hey {studentName} — your milestone is due in {daysUntilDue} days.")
                .status(LifecycleMessageStatus.DRAFTED)
                .tier(tier)
                .build());
    }

    private LifecycleMessageReviewQueue approvedRow(String tier, String timezone) {
        return repository.save(LifecycleMessageReviewQueue.builder()
                .studentId("student-" + UUID.randomUUID())
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Hey {studentName} — your milestone is due in {daysUntilDue} days.")
                .status(LifecycleMessageStatus.APPROVED)
                .tier(tier)
                .safetyVerdict("PASS")
                .timezone(timezone)
                .build());
    }

    private LifecycleMessageReviewQueue deferredRow(Instant deferredUntil, String timezone) {
        return repository.save(LifecycleMessageReviewQueue.builder()
                .studentId("student-" + UUID.randomUUID())
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Hey {studentName} — your milestone is due in {daysUntilDue} days.")
                .status(LifecycleMessageStatus.DEFERRED)
                .tier("AMBER")
                .safetyVerdict("PASS")
                .deferredUntil(deferredUntil)
                .timezone(timezone)
                .build());
    }

    private LifecycleMessageReviewQueue awaitingVetoRow(String tier, Instant vetoWindowExpiresAt) {
        return repository.save(LifecycleMessageReviewQueue.builder()
                .studentId("student-" + UUID.randomUUID())
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Hey {studentName} — your milestone is due in {daysUntilDue} days.")
                .status(LifecycleMessageStatus.AWAITING_VETO_WINDOW)
                .tier(tier)
                .safetyVerdict("PASS")
                .vetoWindowExpiresAt(vetoWindowExpiresAt)
                .build());
    }

    /** A preference row that is always NOT quiet (start==end degenerate window is "always quiet" —
     * per {@link QuietHoursEvaluator}, so instead disable quiet hours entirely for "not quiet" fixtures). */
    private NotificationPreference notQuietPreference(String studentId) {
        return preferenceRepository.save(NotificationPreference.builder()
                .studentId(studentId)
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .quietHoursEnabled(false)
                .build());
    }

    private NotificationPreference quietPreference(String studentId) {
        return preferenceRepository.save(NotificationPreference.builder()
                .studentId(studentId)
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .quietHoursEnabled(true)
                .quietHoursStart(0)
                .quietHoursEnd(0) // start==end => "always quiet" per QuietHoursEvaluator
                .build());
    }

    // -----------------------------------------------------------------------
    // Happy path — AMBER verify -> approve (AC-8.3: veto window = 0, no wait)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processDrafted: AMBER PASS with no opted-out preference row -> APPROVED within one tick")
    void ambPassNoOptOut_advancesToApproved() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
        assertThat(reloaded.getSafetyVerdict()).isEqualTo("PASS");
        // AC-8.3: no wait on veto_window_expires_at, still populated from openVetoWindow() but irrelevant.
    }

    // -----------------------------------------------------------------------
    // Fail-closed (AC-8.1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processDrafted: verifier returns FAIL -> SAFETY_REJECTED, never APPROVED")
    void verifierFail_landsSafetyRejected() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.fail("unknown placeholder"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SAFETY_REJECTED);
        assertThat(reloaded.getSafetyVerdict()).isEqualTo("FAIL");
    }

    @Test
    @DisplayName("processDrafted: verifier throws (simulating circuit-open/timeout) -> fail-closed SAFETY_REJECTED, other rows still process")
    void verifierThrows_failsClosedAndContinuesBatch() {
        LifecycleMessageReviewQueue throwingRow = draftedRow("AMBER");
        LifecycleMessageReviewQueue healthyRow = draftedRow("AMBER");

        when(verifier.verify(anyString(), anyString()))
                .thenThrow(new RuntimeException("circuit open"))
                .thenReturn(SafetyVerdict.pass("all rules passed"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloadedThrowing = repository.findById(throwingRow.getId()).orElseThrow();
        assertThat(reloadedThrowing.getStatus()).isEqualTo(LifecycleMessageStatus.SAFETY_REJECTED);

        // The batch continues — the second (healthy) row is still processed and reaches APPROVED.
        LifecycleMessageReviewQueue reloadedHealthy = repository.findById(healthyRow.getId()).orElseThrow();
        assertThat(reloadedHealthy.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // Flag OFF (AC-10.2)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("poll(): sendGate.enabled=false -> zero verify/approve activity, row stays DRAFTED")
    void flagOff_noOp() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        flags.getSendGate().setEnabled(false);

        poller.poll();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
        assertThat(reloaded.getSafetyVerdict()).isNull();
        org.mockito.Mockito.verifyNoInteractions(verifier);
    }

    // -----------------------------------------------------------------------
    // Opt-out at approve (AC-7.1 origin — regression-tested again in NS-BE-4b)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processDrafted: PASS + in-app disabled preference -> VETOED (opt-out), never APPROVED")
    void optedOut_neverApproved() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        preferenceRepository.save(NotificationPreference.builder()
                .studentId(row.getStudentId())
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .inAppEnabled(false)
                .build());
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.VETOED);
        assertThat(reloaded.getVetoReason()).isEqualTo("opt-out");
    }

    @Test
    @DisplayName("processDrafted: PASS + no preference row -> not opted out -> APPROVED")
    void noPreferenceRow_notOptedOut_approved() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // RED tier (out-of-scope this increment): no auto-approve
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processDrafted: PASS + RED tier -> left AWAITING_VETO_WINDOW, not auto-approved")
    void redTier_notAutoApproved() {
        LifecycleMessageReviewQueue row = draftedRow("RED");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        steps.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
    }

    // =========================================================================
    // NS-BE-4b — step B (send/defer)
    // =========================================================================

    @Test
    @DisplayName("processApprovedForSend: not quiet, not opted out -> deliver + SENT")
    void approvedNotQuietNotOptedOut_deliversAndMarksSent() {
        LifecycleMessageReviewQueue row = approvedRow("AMBER", "UTC");
        notQuietPreference(row.getStudentId());
        when(notificationService.deliver(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        steps.processApprovedForSend();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
        assertThat(reloaded.getSentAt()).isNotNull();
        verify(notificationService, times(1)).deliver(
                org.mockito.ArgumentMatchers.eq(row.getStudentId()),
                org.mockito.ArgumentMatchers.eq(NotificationType.MILESTONE_DUE_SOON),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("processApprovedForSend: in quiet hours -> DEFERRED, no delivery")
    void approvedInQuietHours_defersWithoutDelivery() {
        LifecycleMessageReviewQueue row = approvedRow("AMBER", "UTC");
        quietPreference(row.getStudentId());

        steps.processApprovedForSend();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.DEFERRED);
        assertThat(reloaded.getDeferredUntil()).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("processApprovedForSend: no preference row -> not quiet -> delivered any time (Edge-Case)")
    void approvedNoPreferenceRow_deliveredAnyTime() {
        LifecycleMessageReviewQueue row = approvedRow("AMBER", "UTC");
        when(notificationService.deliver(any(), any(), any(), any(), any(), any())).thenReturn(null);

        steps.processApprovedForSend();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
    }

    @Test
    @DisplayName("processApprovedForSend: opted-out mid-flight (post-approve) -> SUPPRESSED, never delivered (Edge-Case)")
    void approvedOptedOutMidFlight_suppressedNeverDelivered() {
        LifecycleMessageReviewQueue row = approvedRow("AMBER", "UTC");
        preferenceRepository.save(NotificationPreference.builder()
                .studentId(row.getStudentId())
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .inAppEnabled(false)
                .quietHoursEnabled(false)
                .build());

        steps.processApprovedForSend();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SUPPRESSED);
        assertThat(reloaded.getVetoReason()).isEqualTo("opt-out");
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("processApprovedForSend: delivery failure -> row left APPROVED (not SENT), other rows still process (Edge-Case graceful skip)")
    void deliveryFailure_skipsRowGracefullyBatchContinues() {
        LifecycleMessageReviewQueue failing = approvedRow("AMBER", "UTC");
        LifecycleMessageReviewQueue healthy = approvedRow("AMBER", "UTC");
        notQuietPreference(failing.getStudentId());
        notQuietPreference(healthy.getStudentId());

        when(notificationService.deliver(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("since-invalidated metadata reference"))
                .thenReturn(null);

        steps.processApprovedForSend();

        LifecycleMessageReviewQueue reloadedFailing = repository.findById(failing.getId()).orElseThrow();
        assertThat(reloadedFailing.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);

        LifecycleMessageReviewQueue reloadedHealthy = repository.findById(healthy.getId()).orElseThrow();
        assertThat(reloadedHealthy.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
    }

    // =========================================================================
    // NS-BE-4b — step C (deferral drain)
    // =========================================================================

    @Test
    @DisplayName("processDueDeferrals: DEFERRED past due, no longer quiet -> deliver + SENT once (AC-7.2/7.3)")
    void dueDeferral_drainsToSentOnce() {
        LifecycleMessageReviewQueue row = deferredRow(Instant.now().minus(1, ChronoUnit.HOURS), "UTC");
        notQuietPreference(row.getStudentId());
        when(notificationService.deliver(any(), any(), any(), any(), any(), any())).thenReturn(null);

        steps.processDueDeferrals();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
        verify(notificationService, times(1)).deliver(any(), any(), any(), any(), any(), any());

        // Idempotency (AC-8.2): draining again finds no DEFERRED rows left; deliver() is not
        // called a second time for the same row.
        steps.processDueDeferrals();
        verify(notificationService, times(1)).deliver(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processDueDeferrals: still quiet on drain (window shifted) -> left DEFERRED, no delivery")
    void dueDeferralStillQuiet_leftDeferred() {
        LifecycleMessageReviewQueue row = deferredRow(Instant.now().minus(1, ChronoUnit.HOURS), "UTC");
        quietPreference(row.getStudentId());

        steps.processDueDeferrals();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.DEFERRED);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("processDueDeferrals: opted-out mid-flight during defer -> SUPPRESSED, never delivered (Edge-Case)")
    void deferredOptedOutMidFlight_suppressedNeverDelivered() {
        LifecycleMessageReviewQueue row = deferredRow(Instant.now().minus(1, ChronoUnit.HOURS), "UTC");
        preferenceRepository.save(NotificationPreference.builder()
                .studentId(row.getStudentId())
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .inAppEnabled(false)
                .quietHoursEnabled(false)
                .build());

        steps.processDueDeferrals();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SUPPRESSED);
        assertThat(reloaded.getVetoReason()).isEqualTo("opt-out");
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("processDueDeferrals: not-yet-due DEFERRED row is not claimed")
    void deferralNotYetDue_notClaimed() {
        LifecycleMessageReviewQueue row = deferredRow(Instant.now().plus(1, ChronoUnit.HOURS), "UTC");

        steps.processDueDeferrals();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.DEFERRED);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    // =========================================================================
    // NS-BE-4b — step D (expired veto window backlog / RED safety net, ADR-10)
    // =========================================================================

    @Test
    @DisplayName("processExpiredVetoWindows: AMBER row past expiry -> auto-approved")
    void expiredVetoWindowAmber_autoApproved() {
        LifecycleMessageReviewQueue row = awaitingVetoRow("AMBER", Instant.now().minus(10, ChronoUnit.MINUTES));

        steps.processExpiredVetoWindows();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
    }

    @Test
    @DisplayName("processExpiredVetoWindows: RED row past expiry -> left untouched (P2/out-of-scope)")
    void expiredVetoWindowRed_leftUntouched() {
        LifecycleMessageReviewQueue row = awaitingVetoRow("RED", Instant.now().minus(10, ChronoUnit.MINUTES));

        steps.processExpiredVetoWindows();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
    }

    @Test
    @DisplayName("processExpiredVetoWindows: not-yet-expired row is not claimed")
    void expiredVetoWindowNotYetExpired_notClaimed() {
        LifecycleMessageReviewQueue row = awaitingVetoRow("AMBER", Instant.now().plus(10, ChronoUnit.MINUTES));

        steps.processExpiredVetoWindows();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
    }

    // =========================================================================
    // NS-BE-4b — full walk + opt-out-at-approve regression (AC-7.1)
    // =========================================================================

    @Test
    @DisplayName("full walk: DRAFTED -> verify/approve -> send -> SENT within one poll() when never quiet/opted-out")
    void fullWalk_draftedToSentInOnePoll() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        notQuietPreference(row.getStudentId());
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));
        when(notificationService.deliver(any(), any(), any(), any(), any(), any())).thenReturn(null);

        poller.poll();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
    }

    @Test
    @DisplayName("AC-7.1 regression: opt-out at approve (pre-approve, via existing veto path) still never reaches APPROVED after NS-BE-4b's poll() wiring")
    void ac71Regression_optOutAtApproveViaFullPoll() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        preferenceRepository.save(NotificationPreference.builder()
                .studentId(row.getStudentId())
                .notificationType(NotificationType.MILESTONE_DUE_SOON)
                .inAppEnabled(false)
                .build());
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        poller.poll();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.VETOED);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
    }

    // -----------------------------------------------------------------------
    // Concurrent claim (SKIP LOCKED) — two poller instances, one wins per row
    // -----------------------------------------------------------------------

    /**
     * Disables the outer class's default per-test transactional rollback so each thread can
     * open its own real, independently-committed transaction against the shared H2 (PostgreSQL
     * mode) database — required to genuinely exercise {@code FOR UPDATE SKIP LOCKED} row
     * contention between two concurrent {@code processDrafted()} calls.
     *
     * <p><b>Scope note:</b> this is a lower-level test of the REPOSITORY claim query's
     * {@code SKIP LOCKED} contention behavior only — it externally supplies a
     * {@link TransactionTemplate} around a plain-POJO {@code steps} instance (no Spring AOP
     * proxy in play), so it proves the SQL-level locking semantics but does NOT prove
     * production's actual transaction boundary is engaged (that requires calling through a
     * real proxied bean). The real-proxy, real-boundary guarantee — that
     * {@link LifecycleSendGatePoller#poll()} itself, called via its actual Spring proxy with
     * NO externally-supplied transaction, holds the row lock for the whole claim+process
     * batch — is covered by {@link LifecycleSendGatePollerRealProxyConcurrencyTest}, which is the test
     * that would have failed against the old self-invoking {@code poll()} and passes only
     * because {@code poll()} now calls through the separate {@link LifecycleSendGatePollerSteps}
     * bean's proxy.
     */
    @Nested
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class ConcurrentClaim {

        @Autowired
        private PlatformTransactionManager transactionManager;

        @AfterEach
        void cleanup() {
            repository.deleteAll();
        }

        @Test
        @DisplayName("repository-level: two concurrent processDrafted() calls never double-approve the same row (SKIP LOCKED)")
        void concurrentPollers_oneWinsPerRow() throws InterruptedException {
            when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

            List<UUID> rowIds = List.of(
                    draftedRow("AMBER").getId(),
                    draftedRow("AMBER").getId(),
                    draftedRow("AMBER").getId());

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger(0);

            Runnable task = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    txTemplate.executeWithoutResult(status -> steps.processDrafted());
                } catch (Exception ex) {
                    errors.incrementAndGet();
                }
            };

            pool.submit(task);
            pool.submit(task);
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // No transitions should have thrown an IllegalStateException surfaced as a task error —
            // SKIP LOCKED means the second thread simply claims zero (or the remaining) rows.
            assertThat(errors.get()).isZero();

            for (UUID id : rowIds) {
                LifecycleMessageReviewQueue reloaded = repository.findById(id).orElseThrow();
                assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.APPROVED);
            }
        }
    }
}
