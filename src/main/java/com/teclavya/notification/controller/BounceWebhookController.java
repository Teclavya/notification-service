package com.teclavya.notification.controller;

import com.teclavya.notification.dto.request.BounceWebhookRequest;
import com.teclavya.notification.service.SuppressionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint for receiving delivery status notifications / bounce webhooks from SMTP providers.
 * Hard bounces automatically upsert suppression records preventing repeat delivery.
 */
@RestController
@RequestMapping("/api/v1/notifications/webhook")
@RequiredArgsConstructor
@Slf4j
public class BounceWebhookController {

    private final SuppressionService suppressionService;

    @Value("${notification.webhook.secret:${NOTIFICATION_WEBHOOK_SECRET:}}")
    private String configuredSecret;

    @PostMapping("/bounce")
    public ResponseEntity<Map<String, String>> handleBounce(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody @Valid BounceWebhookRequest request) {

        if (!StringUtils.hasText(configuredSecret) || !configuredSecret.equals(secret)) {
            log.warn("Unauthorized bounce webhook attempt for email '{}'", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid webhook secret"));
        }

        String type = request.getBounceType().toUpperCase().trim();
        if ("HARD".equals(type) || "HARD_BOUNCE".equals(type)) {
            suppressionService.suppressEmail(
                    request.getEmail(),
                    "HARD_BOUNCE",
                    StringUtils.hasText(request.getSource()) ? request.getSource() : "SMTP_BOUNCE_WEBHOOK",
                    request.getNotes()
            );
            log.warn("email_hard_bounce_received to='{}' source='{}'", request.getEmail(), request.getSource());
        } else {
            log.info("email_soft_bounce_received to='{}' source='{}' notes='{}'",
                    request.getEmail(), request.getSource(), request.getNotes());
        }

        return ResponseEntity.ok(Map.of("status", "RECORDED"));
    }
}
