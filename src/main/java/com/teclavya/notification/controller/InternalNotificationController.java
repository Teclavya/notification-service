package com.teclavya.notification.controller;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications/internal")
@RequiredArgsConstructor
@Slf4j
public class InternalNotificationController {

    private final NotificationService notificationService;

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
