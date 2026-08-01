package com.teclavya.notification.controller;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.response.GatedSendResponse;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
import com.teclavya.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalNotificationController {

    private final NotificationService notificationService;
    private final LifecycleQueueService lifecycleQueueService;

    /**
     * Service-to-service send — no JWT required.
     * IMPORTANT: In production this endpoint is protected by network policy (VPS-internal only).
     */
    @PostMapping("/send")
    public ResponseEntity<NotificationDto> sendInternal(
            @RequestBody @Valid InternalSendRequest request) {
        log.info("Internal notification from service='{}' for student='{}'",
                request.getSourceService(), request.getStudentId());
        NotificationDto dto = notificationService.sendNotificationInternal(request);
        return ResponseEntity.ok(dto);
    }

    /**
     * Service-to-service gated send — no JWT required (identical auth/transport posture
     * to {@link #sendInternal}, network-policy protected, VPS-internal only).
     * Additive endpoint (ADR-2): does NOT repoint /send. Routes into the lifecycle
     * ethical send-gate (enqueue → DRAFTED) instead of immediate delivery; the row is
     * then driven asynchronously by the LifecycleSendGatePoller.
     */
    @PostMapping("/send-gated")
    public ResponseEntity<GatedSendResponse> sendGated(
            @RequestBody @Valid InternalSendRequest request) {
        log.info("Gated internal notification from service='{}' for student='{}'",
                request.getSourceService(), request.getStudentId());
        LifecycleMessageReviewQueue queued = lifecycleQueueService.enqueue(request);
        GatedSendResponse response = GatedSendResponse.builder()
                .queueId(queued.getId().toString())
                .status(queued.getStatus().name())
                .build();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Batch send — for bulk operations like notifying all cohort members.
     */
    @PostMapping("/send-batch")
    public ResponseEntity<List<NotificationDto>> sendBatch(
            @RequestBody @Valid List<InternalSendRequest> requests) {
        log.info("Internal batch notification: {} requests", requests.size());
        List<NotificationDto> results = notificationService.sendNotificationBatch(requests);
        return ResponseEntity.ok(results);
    }
}
