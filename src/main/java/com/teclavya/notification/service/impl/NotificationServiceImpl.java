package com.teclavya.notification.service.impl;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import com.teclavya.notification.entities.*;
import com.teclavya.notification.publisher.NotificationPublisher;
import com.teclavya.notification.repo.*;
import com.teclavya.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationPublisher notificationPublisher;  // NEW

    @Value("${application.notification.max-push-per-day:3}")
    private int maxPushPerDay;

    @Value("${application.notification.max-email-per-day:1}")
    private int maxEmailPerDay;

    @Override
    @Transactional
    public void sendNotification(SendNotificationRequest request) {
        NotificationType type = resolveType(request.getNotificationType());
        NotificationPreference pref = getOrDefaultPreference(request.getStudentId(), type);

        if (pref.isInAppEnabled()) {
            NotificationDto dto = createNotification(
                    request.getStudentId(), type, NotificationChannel.IN_APP,
                    request.getTitle(), request.getBody(),
                    request.getMetadata(), request.getActionUrl());
            notificationPublisher.publishToWebSocket(dto, request.getStudentId());
        }

        if (pref.isEmailEnabled()) {
            long emailsSentToday = notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(
                    request.getStudentId(), NotificationChannel.EMAIL,
                    LocalDateTime.now().toLocalDate().atStartOfDay());
            if (emailsSentToday < maxEmailPerDay) {
                createNotification(request.getStudentId(), type, NotificationChannel.EMAIL,
                        request.getTitle(), request.getBody(),
                        request.getMetadata(), request.getActionUrl());
            }
        }

        if (pref.isPushEnabled()) {
            long pushSentToday = notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(
                    request.getStudentId(), NotificationChannel.PUSH,
                    LocalDateTime.now().toLocalDate().atStartOfDay());
            if (pushSentToday < maxPushPerDay) {
                createNotification(request.getStudentId(), type, NotificationChannel.PUSH,
                        request.getTitle(), request.getBody(),
                        request.getMetadata(), request.getActionUrl());
            }
        }
    }

    @Override
    @Transactional
    public NotificationDto sendNotificationInternal(InternalSendRequest request) {
        NotificationType type = resolveType(request.getNotificationType());
        return deliver(request.getStudentId(), type, request.getTitle(), request.getBody(),
                request.getMetadata(), request.getActionUrl());
    }

    /**
     * Delivery primitive (NS-BE-4b) — extracted from the body of the former
     * {@code sendNotificationInternal} unchanged: creates an IN_APP notification and publishes
     * it over the websocket. {@code sendNotificationInternal} above now delegates here, and
     * {@code LifecycleSendGatePoller} calls this directly for gated (learning-journey) sends —
     * both callers share exactly this one primitive (design §14b, AC-9.3).
     */
    @Override
    @Transactional
    public NotificationDto deliver(String studentId, NotificationType type, String title, String body,
                                    Map<String, Object> metadata, String actionUrl) {
        NotificationDto dto = createNotification(
                studentId, type, NotificationChannel.IN_APP, title, body, metadata, actionUrl);
        notificationPublisher.publishToWebSocket(dto, studentId);
        return dto;
    }

    @Override
    @Transactional
    public List<NotificationDto> sendNotificationBatch(List<InternalSendRequest> requests) {
        return requests.stream().map(this::sendNotificationInternal).toList();
    }

    @Override
    public List<NotificationDto> getUnreadNotifications(String studentId) {
        return notificationRepository.findByStudentIdAndIsReadFalseOrderByCreatedAtDesc(studentId)
                .stream().map(this::mapToDto).toList();
    }

    @Override
    public List<NotificationDto> getAllNotifications(String studentId, int limit) {
        return notificationRepository.findByStudentIdOrderByCreatedAtDesc(studentId, PageRequest.of(0, limit))
                .stream().map(this::mapToDto).toList();
    }

    @Override
    @Transactional
    public void markAsRead(String notificationId) {
        notificationRepository.findById(UUID.fromString(notificationId)).ifPresent(n -> {
            n.setRead(true);
            notificationRepository.save(n);
        });
    }

    @Override
    @Transactional
    public void markAllAsRead(String studentId) {
        notificationRepository.findByStudentIdAndIsReadFalseOrderByCreatedAtDesc(studentId).forEach(n -> {
            n.setRead(true);
            notificationRepository.save(n);
        });
    }

    @Override
    public long getUnreadCount(String studentId) {
        return notificationRepository.countByStudentIdAndIsReadFalse(studentId);
    }

    @Override
    @Transactional
    public void deleteNotification(String notificationId) {
        notificationRepository.deleteById(UUID.fromString(notificationId));
    }

    @Override
    public List<NotificationPreferenceDto> getPreferences(String studentId) {
        return preferenceRepository.findByStudentId(studentId).stream()
                .map(p -> NotificationPreferenceDto.builder()
                        .notificationType(p.getNotificationType().name())
                        .inAppEnabled(p.isInAppEnabled())
                        .emailEnabled(p.isEmailEnabled())
                        .pushEnabled(p.isPushEnabled())
                        .quietHoursStart(p.getQuietHoursStart())
                        .quietHoursEnd(p.getQuietHoursEnd())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public void updatePreference(String studentId, UpdatePreferencesRequest request) {
        NotificationType type = NotificationType.valueOf(request.getNotificationType());
        NotificationPreference pref = getOrDefaultPreference(studentId, type);
        if (request.getInAppEnabled() != null) pref.setInAppEnabled(request.getInAppEnabled());
        if (request.getEmailEnabled() != null) pref.setEmailEnabled(request.getEmailEnabled());
        if (request.getPushEnabled() != null) pref.setPushEnabled(request.getPushEnabled());
        if (request.getQuietHoursStart() != null) pref.setQuietHoursStart(request.getQuietHoursStart());
        if (request.getQuietHoursEnd() != null) pref.setQuietHoursEnd(request.getQuietHoursEnd());
        preferenceRepository.save(pref);
    }

    private NotificationType resolveType(String raw) {
        try { return NotificationType.valueOf(raw); }
        catch (IllegalArgumentException e) { return NotificationType.GENERAL; }
    }

    private NotificationPreference getOrDefaultPreference(String studentId, NotificationType type) {
        return preferenceRepository.findByStudentIdAndNotificationType(studentId, type)
                .orElse(NotificationPreference.builder()
                        .studentId(studentId).notificationType(type).build());
    }

    private NotificationDto createNotification(
            String studentId, NotificationType type, NotificationChannel channel,
            String title, String body, java.util.Map<String, Object> metadata, String actionUrl) {
        Notification saved = notificationRepository.save(Notification.builder()
                .studentId(studentId).notificationType(type).channel(channel)
                .title(title).body(body).metadata(metadata).actionUrl(actionUrl)
                .build());
        return mapToDto(saved);
    }

    private NotificationDto mapToDto(Notification n) {
        return NotificationDto.builder()
                .notificationId(n.getNotificationId().toString())
                .notificationType(n.getNotificationType().name())
                .channel(n.getChannel().name())
                .title(n.getTitle()).body(n.getBody())
                .metadata(n.getMetadata()).actionUrl(n.getActionUrl())
                .read(n.isRead()).createdAt(n.getCreatedAt())
                .build();
    }
}