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
import static org.mockito.Mockito.when;

/**
 * Integration tests (NS-BE-4a) for {@link LifecycleSendGatePoller} step A (verify → approve),
 * against a real (H2/PostgreSQL-mode) database — same {@code @DataJpaTest} infra as
 * {@link com.teclavya.notification.lifecycle.LifecycleMessageReviewQueueRepositoryTest} and
 * {@link com.teclavya.notification.lifecycle.GatedSendEnqueueIntegrationTest}, per this repo's
 * existing convention.
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
    private NsLifecycleFeatureFlags flags;
    private LifecycleSendGatePoller poller;

    @BeforeEach
    void setUp() {
        verifier = mock(ContentSafetyVerifier.class);
        flags = new NsLifecycleFeatureFlags();
        flags.getSendGate().setEnabled(true);
        poller = new LifecycleSendGatePoller(
                repository, new LifecycleQueueServiceImpl(repository), verifier, flags, preferenceRepository);
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

    // -----------------------------------------------------------------------
    // Happy path — AMBER verify -> approve (AC-8.3: veto window = 0, no wait)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processDrafted: AMBER PASS with no opted-out preference row -> APPROVED within one tick")
    void ambPassNoOptOut_advancesToApproved() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        poller.processDrafted();

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

        poller.processDrafted();

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

        poller.processDrafted();

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

        poller.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.VETOED);
        assertThat(reloaded.getVetoReason()).isEqualTo("opt-out");
    }

    @Test
    @DisplayName("processDrafted: PASS + no preference row -> not opted out -> APPROVED")
    void noPreferenceRow_notOptedOut_approved() {
        LifecycleMessageReviewQueue row = draftedRow("AMBER");
        when(verifier.verify(anyString(), anyString())).thenReturn(SafetyVerdict.pass("all rules passed"));

        poller.processDrafted();

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

        poller.processDrafted();

        LifecycleMessageReviewQueue reloaded = repository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.AWAITING_VETO_WINDOW);
    }

    // -----------------------------------------------------------------------
    // Concurrent claim (SKIP LOCKED) — two poller instances, one wins per row
    // -----------------------------------------------------------------------

    /**
     * Disables the outer class's default per-test transactional rollback so each thread can
     * open its own real, independently-committed transaction against the shared H2 (PostgreSQL
     * mode) database — required to genuinely exercise {@code FOR UPDATE SKIP LOCKED} row
     * contention between two concurrent {@code processDrafted()} calls.
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
        @DisplayName("two concurrent processDrafted() calls never double-approve the same row (SKIP LOCKED)")
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
                    txTemplate.executeWithoutResult(status -> poller.processDrafted());
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
