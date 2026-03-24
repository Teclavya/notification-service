package com.teclavya.notification.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import com.teclavya.notification.service.NotificationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
@Validated
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/send")
    public ResponseEntity<Void> sendNotification(@RequestBody @Valid SendNotificationRequest request) {
        notificationService.sendNotification(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{studentId}/unread")
    public ResponseEntity<List<NotificationDto>> getUnread(@PathVariable String studentId) {
        return ResponseEntity.ok(notificationService.getUnreadNotifications(studentId));
    }

    @GetMapping("/{studentId}/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@PathVariable String studentId) {
        long count = notificationService.getUnreadCount(studentId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<List<NotificationDto>> getAll(
            @PathVariable String studentId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(notificationService.getAllNotifications(studentId, limit));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable String notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{studentId}/read-all")
    public ResponseEntity<Void> markAllAsRead(@PathVariable String studentId) {
        notificationService.markAllAsRead(studentId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(@PathVariable String notificationId) {
        notificationService.deleteNotification(notificationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{studentId}/preferences")
    public ResponseEntity<List<NotificationPreferenceDto>> getPreferences(@PathVariable String studentId) {
        return ResponseEntity.ok(notificationService.getPreferences(studentId));
    }

    @PutMapping("/{studentId}/preferences")
    public ResponseEntity<Void> updatePreference(
            @PathVariable String studentId,
            @RequestBody @Valid UpdatePreferencesRequest request) {
        notificationService.updatePreference(studentId, request);
        return ResponseEntity.ok().build();
    }
}