package com.teclavya.notification.service;

import com.teclavya.notification.entities.EmailSuppression;
import com.teclavya.notification.repo.EmailSuppressionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuppressionServiceTest {

    @Mock
    private EmailSuppressionRepository suppressionRepository;

    private SuppressionService suppressionService;

    @BeforeEach
    void setUp() {
        suppressionService = new SuppressionService(suppressionRepository);
        ReflectionTestUtils.setField(suppressionService, "suppressionEnabled", true);
    }

    @Test
    @DisplayName("isSuppressed: active suppression in repo returns true")
    void isSuppressed_activeRow_returnsTrue() {
        when(suppressionRepository.existsByEmailIgnoreCaseAndRemovedAtIsNull("bounce@student.edu"))
                .thenReturn(true);

        assertThat(suppressionService.isSuppressed("bounce@student.edu")).isTrue();
    }

    @Test
    @DisplayName("isSuppressed: no active suppression returns false")
    void isSuppressed_noActiveRow_returnsFalse() {
        when(suppressionRepository.existsByEmailIgnoreCaseAndRemovedAtIsNull("valid@student.edu"))
                .thenReturn(false);

        assertThat(suppressionService.isSuppressed("valid@student.edu")).isFalse();
    }

    @Test
    @DisplayName("isSuppressed: flag disabled returns false regardless of repo status")
    void isSuppressed_flagDisabled_returnsFalse() {
        ReflectionTestUtils.setField(suppressionService, "suppressionEnabled", false);

        assertThat(suppressionService.isSuppressed("bounce@student.edu")).isFalse();
    }

    @Test
    @DisplayName("suppressEmail: new email creates active suppression row")
    void suppressEmail_newEmail_createsRow() {
        when(suppressionRepository.findByEmailIgnoreCase("new@bounce.edu")).thenReturn(Optional.empty());
        when(suppressionRepository.save(any(EmailSuppression.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailSuppression saved = suppressionService.suppressEmail(
                "new@bounce.edu", "HARD_BOUNCE", "SMTP_WEBHOOK", "550 User unknown");

        assertThat(saved.getEmail()).isEqualTo("new@bounce.edu");
        assertThat(saved.getSuppressionType()).isEqualTo("HARD_BOUNCE");
        assertThat(saved.getRemovedAt()).isNull();
    }

    @Test
    @DisplayName("suppressEmail: previously removed suppression is re-activated")
    void suppressEmail_existingRemovedRow_reactivates() {
        EmailSuppression existing = EmailSuppression.builder()
                .id(UUID.randomUUID())
                .email("old@bounce.edu")
                .suppressionType("UNSUBSCRIBE")
                .removedAt(LocalDateTime.now().minusDays(5))
                .build();

        when(suppressionRepository.findByEmailIgnoreCase("old@bounce.edu")).thenReturn(Optional.of(existing));
        when(suppressionRepository.save(any(EmailSuppression.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailSuppression reactivated = suppressionService.suppressEmail(
                "old@bounce.edu", "HARD_BOUNCE", "SMTP_WEBHOOK", "550 Mailbox unavailable");

        assertThat(reactivated.getRemovedAt()).isNull();
        assertThat(reactivated.getSuppressionType()).isEqualTo("HARD_BOUNCE");
    }

    @Test
    @DisplayName("removeSuppression: sets removedAt on active row")
    void removeSuppression_activeRow_setsRemovedAt() {
        EmailSuppression existing = EmailSuppression.builder()
                .id(UUID.randomUUID())
                .email("student@college.edu")
                .removedAt(null)
                .build();

        when(suppressionRepository.findByEmailIgnoreCaseAndRemovedAtIsNull("student@college.edu"))
                .thenReturn(Optional.of(existing));

        suppressionService.removeSuppression("student@college.edu");

        ArgumentCaptor<EmailSuppression> captor = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(suppressionRepository).save(captor.capture());
        assertThat(captor.getValue().getRemovedAt()).isNotNull();
    }
}
