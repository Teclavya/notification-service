package com.teclavya.notification.scheduler;

import com.teclavya.notification.repo.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationCleanupScheduler {

    private final NotificationRepository notificationRepository;

    // Runs every hour
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpired() {
        int deleted = notificationRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        log.info("Cleaned up {} expired notifications", deleted);
    }
}
