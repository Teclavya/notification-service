package com.teclavya.notification.scheduler;

import com.teclavya.notification.config.NsLifecycleFeatureFlags;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.verifier.ContentSafetyVerifier;
import com.teclavya.notification.lifecycle.verifier.SafetyVerdict;
import com.teclavya.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NS-BE-4 SEV-1 regression: proves {@link LifecycleSendGatePoller#poll()} itself — called
 * through its REAL Spring proxy, with no test-supplied transaction wrapper of any kind — holds
 * the {@code FOR UPDATE SKIP LOCKED} row lock for the whole claim+verify+approve batch, so two
 * concurrent poller ticks never both process the same DRAFTED row.
 *
 * <p>This is deliberately NOT a {@code @DataJpaTest} with a manually-injected
 * {@link org.springframework.transaction.support.TransactionTemplate} (that shape is what let
 * the original bug hide — it proves the SQL-level lock semantics but not that production's own
 * transaction boundary is ever engaged). Here the full application context is booted
 * ({@code @SpringBootTest}), the {@code poller} bean is the ACTUAL Spring-proxied singleton, and
 * each thread calls {@code poller.poll()} exactly as the {@code @Scheduled} trigger would in
 * production — no external transaction is supplied by the test.
 *
 * <p><b>Why this test would fail against the pre-fix code:</b> the old {@code poll()}
 * self-invoked {@code this.processDrafted()} on the same bean, bypassing the AOP proxy — so
 * {@code @Transactional} never engaged, the {@code FOR UPDATE SKIP LOCKED} row lock was released
 * the instant the claim query returned, and a second concurrent {@code poll()} call (racing in
 * the gap between claim and the {@code applyVerdict}/{@code approve} writes) could claim and
 * approve the SAME row a second time — this test's assertion that {@code deliver()}/approve
 * activity happens EXACTLY ONCE per row would then flake/fail under real contention. Against the
 * fix (steps live on the separate, genuinely-proxied {@link LifecycleSendGatePollerSteps} bean),
 * the row lock is held for the whole step, so SKIP LOCKED correctly serializes the two ticks.
 */
@SpringBootTest
@ActiveProfiles("test")
class LifecycleSendGatePollerRealProxyConcurrencyTest {

    @Autowired
    private LifecycleSendGatePoller poller;

    @Autowired
    private LifecycleMessageReviewQueueRepository repository;

    @Autowired
    private NsLifecycleFeatureFlags featureFlags;

    @MockBean
    private ContentSafetyVerifier contentSafetyVerifier;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private com.teclavya.notification.lifecycle.analytics.LifecycleEventEmitter lifecycleEventEmitter;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        featureFlags.getSendGate().setEnabled(true);
        when(contentSafetyVerifier.verify(anyString(), anyString()))
                .thenReturn(SafetyVerdict.pass("all rules passed"));
        when(notificationService.deliver(any(), any(), any(), any(), any(), any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
        featureFlags.getSendGate().setEnabled(false);
    }

    private LifecycleMessageReviewQueue draftedRow() {
        return repository.save(LifecycleMessageReviewQueue.builder()
                .studentId("student-" + UUID.randomUUID())
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Hey {studentName} — your milestone is due in {daysUntilDue} days.")
                .status(LifecycleMessageStatus.DRAFTED)
                .tier("AMBER")
                .build());
    }

    @Test
    @DisplayName("real-proxy poll(): two concurrent ticks never both approve the same DRAFTED row "
            + "(would fail on the pre-fix self-invoking poll())")
    void concurrentRealPollTicks_oneWinsPerRow_realTransactionBoundary() throws InterruptedException {
        List<UUID> rowIds = List.of(draftedRow().getId(), draftedRow().getId(), draftedRow().getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        Runnable tick = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                // The genuine production entry point — no TransactionTemplate, no manual tx.
                poller.poll();
            } catch (Exception ex) {
                errors.incrementAndGet();
            }
        };

        pool.submit(tick);
        pool.submit(tick);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();

        // Every row reaches a terminal claimed state exactly once — never re-processed by both
        // concurrent ticks. AMBER + veto-window=0 -> APPROVED then delivered -> SENT in the same
        // poll() (step A followed by step B), so the fully-settled end state is SENT.
        for (UUID id : rowIds) {
            LifecycleMessageReviewQueue reloaded = repository.findById(id).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(LifecycleMessageStatus.SENT);
        }

        // The decisive proof: exactly one deliver() call per row, never two — a held row lock
        // (real transaction boundary) is the only thing that can guarantee this under real
        // concurrent contention.
        verify(notificationService, times(rowIds.size())).deliver(any(), any(), any(), any(), any(), any());
    }
}
