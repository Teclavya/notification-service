package com.teclavya.notification.repo;

import com.teclavya.notification.entities.PublicEnquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PublicEnquiryRepository extends JpaRepository<PublicEnquiry, UUID> {
}
