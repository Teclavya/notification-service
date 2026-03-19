package com.teclavya.notification.repo;

import com.teclavya.notification.entities.NotificationPreference;
import com.teclavya.notification.entities.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    List<NotificationPreference> findByStudentId(String studentId);
    Optional<NotificationPreference> findByStudentIdAndNotificationType(String studentId, NotificationType type);
}
