package com.teclavya.notification.controller;

import com.teclavya.notification.dto.request.PublicEnquiryRequest;
import com.teclavya.notification.dto.response.PublicEnquiryResponse;
import com.teclavya.notification.service.PublicEnquiryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@RestController
@RequestMapping("/api/v1/notifications/public")
@RequiredArgsConstructor
@Slf4j
public class PublicEnquiryController {

    private static final int MAX_REQUESTS_PER_WINDOW = 5;
    private static final long WINDOW_SECONDS = 900L; // 15 minutes
    private static final int MAX_TRACKED_IPS = 10_000;

    private final PublicEnquiryService enquiryService;
    private final Map<String, Deque<Instant>> rateLimitWindows = new ConcurrentHashMap<>();

    @PostMapping("/enquiries")
    public ResponseEntity<PublicEnquiryResponse> submitEnquiry(
            @RequestBody @Valid PublicEnquiryRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);
        if (!allowRequest(clientIp)) {
            log.warn("public_enquiry_rate_limited");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            PublicEnquiryResponse response = enquiryService.submit(request, clientIp);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (MailException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid enquiry request"));
    }

    private boolean allowRequest(String clientIp) {
        if (rateLimitWindows.size() > MAX_TRACKED_IPS) {
            rateLimitWindows.clear();
        }
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        Deque<Instant> timestamps = rateLimitWindows.computeIfAbsent(clientIp, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
                return false;
            }
            timestamps.addLast(Instant.now());
            return true;
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
