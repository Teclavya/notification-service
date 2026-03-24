package com.teclavya.notification.repo;

import com.teclavya.notification.entities.Notification;
import com.teclavya.notification.entities.NotificationChannel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByStudentIdAndIsReadFalseOrderByCreatedAtDesc(String studentId);

    List<Notification> findByStudentIdOrderByCreatedAtDesc(String studentId, Pageable pageable);

    long countByStudentIdAndChannelAndCreatedAtAfter(String studentId, NotificationChannel channel, LocalDateTime after);

    long countByStudentIdAndIsReadFalse(String studentId);

    int deleteByExpiresAtBefore(LocalDateTime before);
}
