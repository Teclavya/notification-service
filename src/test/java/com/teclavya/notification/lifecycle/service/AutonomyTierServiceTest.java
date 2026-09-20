package com.teclavya.notification.lifecycle.service;

import com.teclavya.notification.dto.response.AutonomyMetricsResponse;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomyTierServiceTest {

    @Mock
    private LifecycleMessageReviewQueueRepository repository;

    private Clock fixedClock;
    private AutonomyTierService service;

    private final Instant now = Instant.parse("2026-09-20T12:00:00Z");

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        service = new AutonomyTierService(repository, fixedClock);
    }

    @Test
    @DisplayName("Empty repository returns AMBER with 0 clean days and 30 days to graduation")
    void emptyRepository_returnsAmber() {
        when(repository.count()).thenReturn(0L);
        when(repository.countByStatus(LifecycleMessageStatus.VETOED)).thenReturn(0L);

        AutonomyMetricsResponse metrics = service.calculateMetrics();

        assertThat(metrics.getCurrentTier()).isEqualTo("AMBER");
        assertThat(metrics.getConsecutiveCleanDays()).isEqualTo(0);
        assertThat(metrics.getDaysToGraduation()).isEqualTo(30);
        assertThat(metrics.getTotalEvaluated()).isEqualTo(0);
        assertThat(metrics.getTotalVetoed()).isEqualTo(0);
        assertThat(metrics.isGraduated()).isFalse();
    }

    @Test
    @DisplayName("Recent veto resets clean days and keeps tier AMBER")
    void recentVeto_resetsCleanDays() {
        Instant vetoAt = now.minus(5, ChronoUnit.DAYS);
        LifecycleMessageReviewQueue vetoedRow = LifecycleMessageReviewQueue.builder()
                .status(LifecycleMessageStatus.VETOED)
                .updatedAt(vetoAt)
                .build();

        when(repository.count()).thenReturn(50L);
        when(repository.countByStatus(LifecycleMessageStatus.VETOED)).thenReturn(2L);
        when(repository.findTopByStatusOrderByUpdatedAtDesc(LifecycleMessageStatus.VETOED))
                .thenReturn(Optional.of(vetoedRow));

        AutonomyMetricsResponse metrics = service.calculateMetrics();

        assertThat(metrics.getCurrentTier()).isEqualTo("AMBER");
        assertThat(metrics.getConsecutiveCleanDays()).isEqualTo(5);
        assertThat(metrics.getDaysToGraduation()).isEqualTo(25);
        assertThat(metrics.getTotalEvaluated()).isEqualTo(50);
        assertThat(metrics.getTotalVetoed()).isEqualTo(2);
        assertThat(metrics.isGraduated()).isFalse();
    }

    @Test
    @DisplayName("30 consecutive clean days with >= 10 messages graduates to GREEN tier")
    void thirtyCleanDays_graduatesToGreen() {
        Instant earliestAt = now.minus(32, ChronoUnit.DAYS);
        LifecycleMessageReviewQueue earliestRow = LifecycleMessageReviewQueue.builder()
                .createdAt(earliestAt)
                .build();

        when(repository.count()).thenReturn(100L);
        when(repository.countByStatus(LifecycleMessageStatus.VETOED)).thenReturn(0L);
        when(repository.findTopByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(earliestRow));

        AutonomyMetricsResponse metrics = service.calculateMetrics();

        assertThat(metrics.getCurrentTier()).isEqualTo("GREEN");
        assertThat(metrics.getConsecutiveCleanDays()).isEqualTo(32);
        assertThat(metrics.getDaysToGraduation()).isEqualTo(0);
        assertThat(metrics.getTotalEvaluated()).isEqualTo(100);
        assertThat(metrics.getTotalVetoed()).isEqualTo(0);
        assertThat(metrics.isGraduated()).isTrue();
    }
}
