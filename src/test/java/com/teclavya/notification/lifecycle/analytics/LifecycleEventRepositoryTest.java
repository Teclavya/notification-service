package com.teclavya.notification.lifecycle.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataJpaTest slice for {@link LifecycleEventRepository}.
 * Uses the same H2JsonbDialect + test property source pattern as the existing
 * {@code LifecycleMessageReviewQueueRepositoryTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_levent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=com.teclavya.notification.config.H2JsonbDialect",
        "spring.jpa.properties.hibernate.dialect=com.teclavya.notification.config.H2JsonbDialect",
        "spring.flyway.enabled=false"
})
class LifecycleEventRepositoryTest {

    @Autowired
    private LifecycleEventRepository repository;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LifecycleEvent save(String eventType, String studentId, UUID messageId) {
        return repository.save(LifecycleEvent.builder()
                .eventType(eventType)
                .studentId(studentId)
                .messageId(messageId)
                .build());
    }

    // -----------------------------------------------------------------------
    // findByEventTypeAndCreatedAtBetween
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByEventTypeAndCreatedAtBetween returns matching rows within range")
    void findByEventTypeAndCreatedAtBetween_returnsMatchingRows() {
        save("lifecycle_message.send_blocked", "stu-1", UUID.randomUUID());
        save("lifecycle_message.approved",     "stu-2", null);

        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to   = Instant.now().plus(1, ChronoUnit.HOURS);

        List<LifecycleEvent> results = repository.findByEventTypeAndCreatedAtBetween(
                "lifecycle_message.send_blocked", from, to);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStudentId()).isEqualTo("stu-1");
    }

    @Test
    @DisplayName("findByEventTypeAndCreatedAtBetween returns empty list when no match in range")
    void findByEventTypeAndCreatedAtBetween_noMatch_returnsEmpty() {
        save("lifecycle_message.sent", "stu-x", null);

        Instant from = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant to   = Instant.now().plus(2, ChronoUnit.HOURS);

        List<LifecycleEvent> results = repository.findByEventTypeAndCreatedAtBetween(
                "lifecycle_message.sent", from, to);

        assertThat(results).isEmpty();
    }

    // -----------------------------------------------------------------------
    // findByStudentIdOrderByCreatedAtDesc
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByStudentIdOrderByCreatedAtDesc returns all events for student, newest first")
    void findByStudentIdOrderByCreatedAtDesc_returnsStudentEvents() throws InterruptedException {
        save("lifecycle_message.approved", "stu-A", null);
        Thread.sleep(5);
        save("lifecycle_message.sent",     "stu-A", null);
        save("lifecycle_message.sent",     "stu-B", null); // different student — excluded

        List<LifecycleEvent> results =
                repository.findByStudentIdOrderByCreatedAtDesc("stu-A");

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> "stu-A".equals(e.getStudentId()));
        // Newest first
        assertThat(results.get(0).getCreatedAt())
                .isAfterOrEqualTo(results.get(1).getCreatedAt());
    }

    // -----------------------------------------------------------------------
    // findByMessageId
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findByMessageId returns all events for the given message UUID")
    void findByMessageId_returnsMatchingEvents() {
        UUID mid = UUID.randomUUID();
        save("lifecycle_message.safety_checked",  "stu-C", mid);
        save("lifecycle_message.approved",         "stu-C", mid);
        save("lifecycle_message.send_blocked",     "stu-C", UUID.randomUUID()); // different message

        List<LifecycleEvent> results = repository.findByMessageId(mid);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> mid.equals(e.getMessageId()));
    }

    @Test
    @DisplayName("findByMessageId returns empty list when messageId is null for all rows")
    void findByMessageId_noMatch_returnsEmpty() {
        save("feature_flag.updated", "stu-D", null);

        List<LifecycleEvent> results = repository.findByMessageId(UUID.randomUUID());

        assertThat(results).isEmpty();
    }
}
