package com.teclavya.notification.lifecycle;

import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataJpaTest slice — validates repository query methods against the H2
 * in-memory database using application-test.yaml datasource (MODE=PostgreSQL).
 * Flyway is disabled by application-test.yaml.
 * AutoConfigureTestDatabase(replace=NONE) lets Spring Boot use the configured H2 URL
 * (with MODE=PostgreSQL) so that JSONB + other PG-compat types resolve correctly.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=com.teclavya.notification.config.H2JsonbDialect",
        "spring.jpa.properties.hibernate.dialect=com.teclavya.notification.config.H2JsonbDialect",
        "spring.flyway.enabled=false"
})
class LifecycleMessageReviewQueueRepositoryTest {

    @Autowired
    private LifecycleMessageReviewQueueRepository repository;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LifecycleMessageReviewQueue saveRow(
            LifecycleMessageStatus status,
            String safetyVerdict,
            Instant vetoWindowExpiresAt,
            Instant deferredUntil) {

        LifecycleMessageReviewQueue row = LifecycleMessageReviewQueue.builder()
                .studentId("stu-" + System.nanoTime())
                .notificationType("INACTIVITY_REENGAGEMENT")
                .messageBody("Test body")
                .status(status)
                .tier("AMBER")
                .safetyVerdict(safetyVerdict)
                .vetoWindowExpiresAt(vetoWindowExpiresAt)
                .deferredUntil(deferredUntil)
                .build();
        return repository.save(row);
    }

    // -----------------------------------------------------------------------
    // findByStatusAndVetoWindowExpiresAtBefore
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByStatusAndVetoWindowExpiresAtBefore returns only AWAITING_VETO_WINDOW rows past threshold")
    void expiredVetoWindows_returnsCorrectRows() {
        Instant now = Instant.now();

        // Expired → should be returned
        LifecycleMessageReviewQueue expired = saveRow(
                LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS",
                now.minus(1, ChronoUnit.MINUTES), null);

        // Not yet expired → should NOT be returned
        saveRow(LifecycleMessageStatus.AWAITING_VETO_WINDOW, "PASS",
                now.plus(3, ChronoUnit.MINUTES), null);

        // Different status with past expiry → should NOT be returned
        saveRow(LifecycleMessageStatus.APPROVED, "PASS",
                now.minus(1, ChronoUnit.MINUTES), null);

        List<LifecycleMessageReviewQueue> results =
                repository.findByStatusAndVetoWindowExpiresAtBefore(
                        LifecycleMessageStatus.AWAITING_VETO_WINDOW, now);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(expired.getId());
    }

    // -----------------------------------------------------------------------
    // findByStatusAndDeferredUntilBefore
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByStatusAndDeferredUntilBefore returns only DEFERRED rows past threshold")
    void dueDeferrals_returnsCorrectRows() {
        Instant now = Instant.now();

        // Past defer → should be returned
        LifecycleMessageReviewQueue due = saveRow(
                LifecycleMessageStatus.DEFERRED, "PASS",
                null, now.minus(1, ChronoUnit.HOURS));

        // Future defer → should NOT be returned
        saveRow(LifecycleMessageStatus.DEFERRED, "PASS",
                null, now.plus(5, ChronoUnit.HOURS));

        // Different status → should NOT be returned
        saveRow(LifecycleMessageStatus.APPROVED, "PASS",
                null, now.minus(1, ChronoUnit.HOURS));

        List<LifecycleMessageReviewQueue> results =
                repository.findByStatusAndDeferredUntilBefore(
                        LifecycleMessageStatus.DEFERRED, now);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(due.getId());
    }

    // -----------------------------------------------------------------------
    // findByStatus
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByStatus returns only rows matching the given status")
    void findByStatus_returnsMatchingRows() {
        saveRow(LifecycleMessageStatus.APPROVED, "PASS", null, null);
        saveRow(LifecycleMessageStatus.APPROVED, "PASS", null, null);
        saveRow(LifecycleMessageStatus.DRAFTED, null, null, null);

        List<LifecycleMessageReviewQueue> approved =
                repository.findByStatus(LifecycleMessageStatus.APPROVED);

        assertThat(approved).hasSize(2);
        assertThat(approved).allMatch(r -> r.getStatus() == LifecycleMessageStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // findByStatusOrderByCreatedAtDesc (paginated admin list)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByStatusOrderByCreatedAtDesc returns paginated results newest-first")
    void adminList_returnsPagedNewestFirst() throws InterruptedException {
        // Insert two DRAFTED rows; rely on auto-generated createdAt ordering.
        // A tiny sleep ensures distinct timestamps from the @CreationTimestamp.
        LifecycleMessageReviewQueue first = saveRow(LifecycleMessageStatus.DRAFTED, null, null, null);
        Thread.sleep(10); // ensure distinct createdAt
        LifecycleMessageReviewQueue second = saveRow(LifecycleMessageStatus.DRAFTED, null, null, null);

        Page<LifecycleMessageReviewQueue> page =
                repository.findByStatusOrderByCreatedAtDesc(
                        LifecycleMessageStatus.DRAFTED, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        // Verify newest-first ordering
        List<LifecycleMessageReviewQueue> content = page.getContent();
        for (int i = 0; i < content.size() - 1; i++) {
            assertThat(content.get(i).getCreatedAt())
                    .isAfterOrEqualTo(content.get(i + 1).getCreatedAt());
        }
    }

    @Test
    @DisplayName("findByStatusOrderByCreatedAtDesc with page size 1 returns only one row")
    void adminList_pagination_limitsResults() {
        saveRow(LifecycleMessageStatus.VETOED, null, null, null);
        saveRow(LifecycleMessageStatus.VETOED, null, null, null);
        saveRow(LifecycleMessageStatus.VETOED, null, null, null);

        Page<LifecycleMessageReviewQueue> page =
                repository.findByStatusOrderByCreatedAtDesc(
                        LifecycleMessageStatus.VETOED, PageRequest.of(0, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }
}
