package com.teclavya.notification.lifecycle;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.impl.LifecycleQueueServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test (NS-BE-1) proving the POST /send-gated pipeline creates a REAL
 * DRAFTED row via {@link com.teclavya.notification.lifecycle.service.LifecycleQueueService#enqueue}
 * against a real (H2/PostgreSQL-mode) database — same DataJpaTest infra as
 * {@link LifecycleMessageReviewQueueRepositoryTest}, per this repo's existing convention.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gatedsendtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=com.teclavya.notification.config.H2JsonbDialect",
        "spring.jpa.properties.hibernate.dialect=com.teclavya.notification.config.H2JsonbDialect",
        "spring.flyway.enabled=false"
})
class GatedSendEnqueueIntegrationTest {

    @Autowired
    private LifecycleMessageReviewQueueRepository repository;

    @Test
    @DisplayName("enqueue() persists a real DRAFTED row, retrievable by id from the database")
    void enqueue_persistsRealDraftedRow() {
        LifecycleQueueServiceImpl service = new LifecycleQueueServiceImpl(repository);

        InternalSendRequest req = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("student-integration-1")
                .notificationType("MILESTONE_DUE_SOON")
                .title("Your milestone is due soon")
                .body("Hey — your milestone is due in 2 days.")
                .metadata(Map.of("tier", "AMBER", "timezone", "Asia/Kolkata"))
                .build();

        LifecycleMessageReviewQueue result = service.enqueue(req);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);

        // Re-fetch from the database (not the in-memory returned object) to prove
        // the row was actually persisted, not just constructed in memory.
        Optional<LifecycleMessageReviewQueue> reloaded = repository.findById(result.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(LifecycleMessageStatus.DRAFTED);
        assertThat(reloaded.get().getStudentId()).isEqualTo("student-integration-1");
        assertThat(reloaded.get().getNotificationType()).isEqualTo("MILESTONE_DUE_SOON");
        assertThat(reloaded.get().getTier()).isEqualTo("AMBER");
        assertThat(reloaded.get().getTimezone()).isEqualTo("Asia/Kolkata");
    }
}
