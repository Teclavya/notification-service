package com.teclavya.notification.repo;

import com.teclavya.notification.entities.ProcessedTicketEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedTicketEventRepository extends JpaRepository<ProcessedTicketEvent, String> {
    boolean existsByEventId(String eventId);
}
