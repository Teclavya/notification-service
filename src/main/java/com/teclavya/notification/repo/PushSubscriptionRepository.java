package com.teclavya.notification.repo;

import com.teclavya.notification.entities.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    List<PushSubscription> findByStudentIdAndActiveTrue(String studentId);
}
