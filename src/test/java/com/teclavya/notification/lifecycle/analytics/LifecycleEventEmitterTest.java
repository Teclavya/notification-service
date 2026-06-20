package com.teclavya.notification.lifecycle.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LifecycleEventEmitter}.
 *
 * <p>Verifies two key contracts:
 * <ol>
 *   <li>emit() persists a row with the correct eventType, studentId, messageId, and properties.</li>
 *   <li>emit() swallows a repository exception and does not propagate it (non-fatal guarantee).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class LifecycleEventEmitterTest {

    @Mock
    private LifecycleEventRepository repository;

    private LifecycleEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new LifecycleEventEmitter(repository);
    }

    // -----------------------------------------------------------------------
    // Core emit() — persists correct fields
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("emit() persists a LifecycleEvent row with correct eventType, studentId, messageId, and properties")
    void emit_persistsRowWithCorrectFields() {
        UUID messageId = UUID.randomUUID();
        Map<String, Object> props = Map.of("reason", "quiet-hours");

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<LifecycleEvent> captor = ArgumentCaptor.forClass(LifecycleEvent.class);

        emitter.emit(LifecycleEventEmitter.EVT_SEND_BLOCKED, "student-abc", messageId, props);

        verify(repository).save(captor.capture());
        LifecycleEvent saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(LifecycleEventEmitter.EVT_SEND_BLOCKED);
        assertThat(saved.getStudentId()).isEqualTo("student-abc");
        assertThat(saved.getMessageId()).isEqualTo(messageId);
        assertThat(saved.getProperties()).containsEntry("reason", "quiet-hours");
    }

    @Test
    @DisplayName("emit() with null messageId persists a row with null messageId")
    void emit_nullMessageId_persistsNullMessageId() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LifecycleEvent> captor = ArgumentCaptor.forClass(LifecycleEvent.class);

        emitter.emit(LifecycleEventEmitter.EVT_FEATURE_FLAG_UPDATED, "student-xyz", null,
                Map.of("flagName", "lifecycle_send_gate", "newValue", true));

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMessageId()).isNull();
        assertThat(captor.getValue().getProperties()).containsKey("flagName");
    }

    // -----------------------------------------------------------------------
    // Non-fatal guarantee — repository exception is swallowed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("emit() swallows a repository exception — caller sees no exception (non-fatal)")
    void emit_repositoryThrows_swallowsException() {
        doThrow(new RuntimeException("DB is down")).when(repository).save(any());

        // Must NOT throw
        assertThatCode(() ->
                emitter.emit(LifecycleEventEmitter.EVT_SEND_BLOCKED, "student-1",
                        UUID.randomUUID(), Map.of("reason", "veto")))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Convenience emitters — verify they call emit() with correct type
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("emitSendBlocked() uses EVT_SEND_BLOCKED and includes reason in properties")
    void emitSendBlocked_usesCorrectTypeAndReason() {
        UUID mid = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LifecycleEvent> captor = ArgumentCaptor.forClass(LifecycleEvent.class);

        emitter.emitSendBlocked("stu-1", mid, "quiet-hours");

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(LifecycleEventEmitter.EVT_SEND_BLOCKED);
        assertThat(captor.getValue().getProperties()).containsEntry("reason", "quiet-hours");
    }

    @Test
    @DisplayName("emitFeatureFlagUpdated() uses EVT_FEATURE_FLAG_UPDATED, null messageId, flag props")
    void emitFeatureFlagUpdated_correctFields() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LifecycleEvent> captor = ArgumentCaptor.forClass(LifecycleEvent.class);

        emitter.emitFeatureFlagUpdated("stu-2", "lifecycle_send_gate", true);

        verify(repository).save(captor.capture());
        LifecycleEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(LifecycleEventEmitter.EVT_FEATURE_FLAG_UPDATED);
        assertThat(saved.getMessageId()).isNull();
        assertThat(saved.getProperties()).containsEntry("flagName", "lifecycle_send_gate");
        assertThat(saved.getProperties()).containsEntry("newValue", true);
    }

    @Test
    @DisplayName("emitSafetyRejected() swallows repository exception (non-fatal convenience path)")
    void emitSafetyRejected_repositoryThrows_swallows() {
        doThrow(new RuntimeException("timeout")).when(repository).save(any());

        assertThatCode(() ->
                emitter.emitSafetyRejected("stu-3", UUID.randomUUID(), "PII detected"))
                .doesNotThrowAnyException();
    }
}
