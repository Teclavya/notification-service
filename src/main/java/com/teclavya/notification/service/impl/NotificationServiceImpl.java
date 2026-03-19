package com.teclavya.notification.service.impl;

import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import com.teclavya.notification.entities.*;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;

    @Value("${application.notification.max-push-per-day:3}")
    private int maxPushPerDay;

    @Value("${application.notification.max-email-per-day:1}")
    private int maxEmailPerDay;

    @Override
    @Transactional
    public void sendNotification(SendNotificationRequest request) {
        NotificationType type;
        try {
            type = NotificationType.valueOf(request.getNotificationType());
        } catch (IllegalArgumentException e) {
            type = NotificationType.GENERAL;
        }

        NotificationPreference pref = preferenceRepository
                .findByStudentIdAndNotificationType(request.getStudentId(), type)
                .orElse(NotificationPreference.builder()
                        .studentId(request.getStudentId())
                        .notificationType(type)
                        .build());

        // Always create in-app notification
        if (pref.isInAppEnabled()) {
            createNotification(request, NotificationChannel.IN_APP, type);
        }

        // Rate-limited email
        if (pref.isEmailEnabled()) {
            long emailsSentToday = notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(
                    request.getStudentId(), NotificationChannel.EMAIL, LocalDateTime.now().toLocalDate().atStartOfDay());
            if (emailsSentToday < maxEmailPerDay) {
                createNotification(request, NotificationChannel.EMAIL, type);
                log.info("Email notification queued for student '{}'", request.getStudentId());
            }
        }

        // Rate-limited push
        if (pref.isPushEnabled()) {
            long pushSentToday = notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(
                    request.getStudentId(), NotificationChannel.PUSH, LocalDateTime.now().toLocalDate().atStartOfDay());
            if (pushSentToday < maxPushPerDay) {
                createNotification(request, NotificationChannel.PUSH, type);
                log.info("Push notification queued for student '{}'", request.getStudentId());
            }
        }
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
        NotificationPreference pref = preferenceRepository.findByStudentIdAndNotificationType(studentId, type)
                .orElse(NotificationPreference.builder()
                        .studentId(studentId)
                        .notificationType(type)
                        .build());

        if (request.getInAppEnabled() != null) pref.setInAppEnabled(request.getInAppEnabled());
        if (request.getEmailEnabled() != null) pref.setEmailEnabled(request.getEmailEnabled());
        if (request.getPushEnabled() != null) pref.setPushEnabled(request.getPushEnabled());
        if (request.getQuietHoursStart() != null) pref.setQuietHoursStart(request.getQuietHoursStart());
        if (request.getQuietHoursEnd() != null) pref.setQuietHoursEnd(request.getQuietHoursEnd());

        preferenceRepository.save(pref);
    }

    private void createNotification(SendNotificationRequest request, NotificationChannel channel, NotificationType type) {
        notificationRepository.save(Notification.builder()
                .studentId(request.getStudentId())
                .notificationType(type)
                .channel(channel)
                .title(request.getTitle())
                .body(request.getBody())
                .metadata(request.getMetadata())
                .build());
    }

    private NotificationDto mapToDto(Notification n) {
        return NotificationDto.builder()
                .notificationId(n.getNotificationId().toString())
                .notificationType(n.getNotificationType().name())
                .channel(n.getChannel().name())
                .title(n.getTitle())
                .body(n.getBody())
                .metadata(n.getMetadata())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
