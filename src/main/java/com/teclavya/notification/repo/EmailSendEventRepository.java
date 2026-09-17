package com.teclavya.notification.repo;

import com.teclavya.notification.entities.EmailSendEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailSendEventRepository extends JpaRepository<EmailSendEvent, UUID> {

    Optional<EmailSendEvent> findByIdempotencyKey(String idempotencyKey);
}
