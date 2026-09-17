package com.teclavya.notification.repo;

import com.teclavya.notification.entities.EmailSuppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

    Optional<EmailSuppression> findByEmailIgnoreCaseAndRemovedAtIsNull(String email);

    Optional<EmailSuppression> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndRemovedAtIsNull(String email);
}
