package com.teclavya.notification.lifecycle.service;

import com.teclavya.notification.dto.response.AutonomyMetricsResponse;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Evaluates operational autonomy tiers (AMBER vs GREEN) for the lifecycle send-gate.
 *
 * <p>Policy:
 * <ul>
 *   <li>Default Tier: <b>AMBER</b> (human veto window enforced).</li>
 *   <li>Graduation to <b>GREEN</b> (zero veto window / full autonomy) requires
 *       <b>30 consecutive clean days</b> with zero human vetoes and active message throughput.</li>
 *   <li>Any human veto immediately resets the clean-day counter to 0, demoting back to AMBER.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutonomyTierService {

    public static final int GRADUATION_THRESHOLD_DAYS = 30;
    public static final int MIN_MESSAGES_FOR_GRADUATION = 10;

    private final LifecycleMessageReviewQueueRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AutonomyMetricsResponse calculateMetrics() {
        long totalEvaluated = repository.count();
        long totalVetoed = repository.countByStatus(LifecycleMessageStatus.VETOED);

        Instant now = Instant.now(clock);
        long consecutiveCleanDays = 0;

        if (totalVetoed > 0) {
            Optional<LifecycleMessageReviewQueue> latestVetoOpt =
                    repository.findTopByStatusOrderByUpdatedAtDesc(LifecycleMessageStatus.VETOED);
            if (latestVetoOpt.isPresent()) {
                Instant lastVetoAt = latestVetoOpt.get().getUpdatedAt();
                consecutiveCleanDays = ChronoUnit.DAYS.between(lastVetoAt, now);
            }
        } else if (totalEvaluated > 0) {
            Optional<LifecycleMessageReviewQueue> earliestOpt = repository.findTopByOrderByCreatedAtAsc();
            if (earliestOpt.isPresent()) {
                Instant earliestAt = earliestOpt.get().getCreatedAt();
                consecutiveCleanDays = ChronoUnit.DAYS.between(earliestAt, now);
            }
        }

        consecutiveCleanDays = Math.max(0, consecutiveCleanDays);

        boolean graduated = (consecutiveCleanDays >= GRADUATION_THRESHOLD_DAYS)
                && (totalEvaluated >= MIN_MESSAGES_FOR_GRADUATION);
        String currentTier = graduated ? "GREEN" : "AMBER";
        long daysToGraduation = graduated ? 0 : Math.max(0, GRADUATION_THRESHOLD_DAYS - consecutiveCleanDays);

        log.debug("Autonomy metrics evaluated: tier={}, cleanDays={}, daysToGraduation={}, totalEvaluated={}, totalVetoed={}",
                currentTier, consecutiveCleanDays, daysToGraduation, totalEvaluated, totalVetoed);

        return AutonomyMetricsResponse.builder()
                .currentTier(currentTier)
                .consecutiveCleanDays(consecutiveCleanDays)
                .daysToGraduation(daysToGraduation)
                .totalEvaluated(totalEvaluated)
                .totalVetoed(totalVetoed)
                .graduated(graduated)
                .build();
    }
}
