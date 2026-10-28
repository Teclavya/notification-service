package com.teclavya.notification.repo;

import com.teclavya.notification.entities.OnCallDispatchAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OnCallDispatchAuditRepository extends JpaRepository<OnCallDispatchAudit, UUID> {
    List<OnCallDispatchAudit> findByTicketNumberOrderByDispatchedAtDesc(String ticketNumber);
}
