package com.teclavya.notification.controller;

import com.teclavya.notification.dto.request.EditMessageBodyRequest;
import com.teclavya.notification.dto.request.VetoMessageRequest;
import com.teclavya.notification.dto.response.AutonomyMetricsResponse;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.AutonomyTierService;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Operations & Admin REST controller for managing the lifecycle message review queue,
 * human veto interventions, and autonomy tier graduation metrics.
 */
@RestController
@RequestMapping("/api/v1/notifications/admin/lifecycle-queue")
@RequiredArgsConstructor
@Slf4j
public class LifecycleAdminController {

    private final LifecycleQueueService lifecycleQueueService;
    private final LifecycleMessageReviewQueueRepository repository;
    private final AutonomyTierService autonomyTierService;

    /**
     * List queued messages filtered by status, or all messages ordered newest-first.
     */
    @GetMapping
    public ResponseEntity<Page<LifecycleMessageReviewQueue>> list(
            @RequestParam(required = false) LifecycleMessageStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        log.info("Admin list lifecycle queue: status={}, page={}", status, pageable.getPageNumber());
        Page<LifecycleMessageReviewQueue> page = (status == null)
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : lifecycleQueueService.listForAdmin(status, pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * Admin early approval of a message awaiting review.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<LifecycleMessageReviewQueue> approve(@PathVariable UUID id) {
        log.info("Admin approving lifecycle message: id={}", id);
        try {
            LifecycleMessageReviewQueue approved = lifecycleQueueService.approve(id);
            return ResponseEntity.ok(approved);
        } catch (IllegalStateException e) {
            log.warn("Cannot approve message id={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Admin veto intervention — blocks delivery and resets the 30-day clean streak.
     */
    @PostMapping("/{id}/veto")
    public ResponseEntity<LifecycleMessageReviewQueue> veto(
            @PathVariable UUID id,
            @RequestBody @Valid VetoMessageRequest request) {
        log.info("Admin vetoing lifecycle message: id={}, reason={}", id, request.getReason());
        try {
            LifecycleMessageReviewQueue vetoed = lifecycleQueueService.veto(id, request.getReason());
            return ResponseEntity.ok(vetoed);
        } catch (IllegalStateException e) {
            log.warn("Cannot veto message id={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Admin edits message body and resets status to DRAFTED for re-verification.
     */
    @PutMapping("/{id}/body")
    public ResponseEntity<LifecycleMessageReviewQueue> editBody(
            @PathVariable UUID id,
            @RequestBody @Valid EditMessageBodyRequest request) {
        log.info("Admin editing lifecycle message body: id={}", id);
        try {
            LifecycleMessageReviewQueue updated = lifecycleQueueService.editBody(id, request.getBody());
            return ResponseEntity.ok(updated);
        } catch (IllegalStateException e) {
            log.warn("Cannot edit message id={}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Autonomy metrics & graduation countdown tracker.
     */
    @GetMapping("/autonomy-metrics")
    public ResponseEntity<AutonomyMetricsResponse> getAutonomyMetrics() {
        return ResponseEntity.ok(autonomyTierService.calculateMetrics());
    }
}
