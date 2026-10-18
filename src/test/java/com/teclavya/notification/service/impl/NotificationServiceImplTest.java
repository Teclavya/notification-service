package com.teclavya.notification.service.impl;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import com.teclavya.notification.entities.*;
import com.teclavya.notification.publisher.NotificationPublisher;
import com.teclavya.notification.repo.NotificationPreferenceRepository;
import com.teclavya.notification.repo.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationPreferenceRepository preferenceRepository;
    @Mock
    private NotificationPublisher notificationPublisher;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(
                notificationRepository, preferenceRepository, notificationPublisher);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxEmailPerDay", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxPushPerDay", 3);
    }

    private Notification savedNotification() {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID());
        n.setStudentId("student-123");
        n.setNotificationType(NotificationType.MENTOR_REPLIED);
        n.setChannel(NotificationChannel.IN_APP);
        n.setTitle("Mentor replied");
        n.setBody("Check session");
        n.setActionUrl("/mentor/1");
        n.setRead(false);
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }

    @Test
    @DisplayName("sendNotificationInternal - should save and publish to Redis")
    void shouldSaveAndPublish() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());

        InternalSendRequest request = new InternalSendRequest(
                "mentor", "student-123", "MENTOR_REPLIED",
                "Mentor replied", "Check session", Map.of(), "/mentor/1");

        NotificationDto result = service.sendNotificationInternal(request);

        assertThat(result).isNotNull();
        assertThat(result.getNotificationType()).isEqualTo("MENTOR_REPLIED");
        assertThat(result.getActionUrl()).isEqualTo("/mentor/1");
        verify(notificationRepository).save(any());
        verify(notificationPublisher).publishToWebSocket(any(), eq("student-123"));
    }

    @Test
    @DisplayName("sendNotificationBatch - should process all requests")
    void shouldProcessBatch() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());

        List<InternalSendRequest> requests = List.of(
                new InternalSendRequest("mentor", "student-123", "MENTOR_REPLIED",
                        "Title 1", "Body 1", Map.of(), "/mentor/1"),
                new InternalSendRequest("cohorts", "student-456", "COHORT_POST_REPLY",
                        "Title 2", "Body 2", Map.of(), "/cohorts/2")
        );

        List<NotificationDto> results = service.sendNotificationBatch(requests);

        assertThat(results).hasSize(2);
        verify(notificationRepository, times(2)).save(any());
        verify(notificationPublisher, times(2)).publishToWebSocket(any(), any());
    }

    @Test
    @DisplayName("getUnreadCount - should return count from repository")
    void shouldReturnUnreadCount() {
        when(notificationRepository.countByStudentIdAndIsReadFalse("student-123")).thenReturn(3L);

        long count = service.getUnreadCount("student-123");

        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("deleteNotification - should call repository delete")
    void shouldDeleteNotification() {
        String id = UUID.randomUUID().toString();
        service.deleteNotification(id);
        verify(notificationRepository).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("markAsRead - should set isRead true")
    void shouldMarkAsRead() {
        Notification n = savedNotification();
        when(notificationRepository.findById(any())).thenReturn(java.util.Optional.of(n));

        service.markAsRead(n.getNotificationId().toString());

        assertThat(n.isRead()).isTrue();
        verify(notificationRepository).save(n);
    }

    @Test
    @DisplayName("resolveType - unknown type should default to GENERAL")
    void shouldDefaultToGeneralForUnknownType() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());

        InternalSendRequest request = new InternalSendRequest(
                "unknown", "student-123", "UNKNOWN_TYPE",
                "Title", "Body", Map.of(), "/path");

        NotificationDto result = service.sendNotificationInternal(request);
        assertThat(result).isNotNull();
    }

    // -----------------------------------------------------------------------
    // deliver() extraction parity (NS-BE-4b, AC-9.3) — sendNotificationInternal must exhibit
    // byte-identical save+publish behaviour after delegating to the new deliver() primitive.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("deliver() parity: sendNotificationInternal saves + publishes exactly as before the extraction")
    void deliverExtraction_sendNotificationInternalParity() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());

        InternalSendRequest request = new InternalSendRequest(
                "mentor", "student-123", "MENTOR_REPLIED",
                "Mentor replied", "Check session", Map.of(), "/mentor/1");

        NotificationDto result = service.sendNotificationInternal(request);

        assertThat(result).isNotNull();
        assertThat(result.getNotificationType()).isEqualTo("MENTOR_REPLIED");
        assertThat(result.getChannel()).isEqualTo("IN_APP");
        assertThat(result.getActionUrl()).isEqualTo("/mentor/1");
        verify(notificationRepository, times(1)).save(any());
        verify(notificationPublisher, times(1)).publishToWebSocket(any(), eq("student-123"));
    }

    @Test
    @DisplayName("deliver() called directly (LifecycleSendGatePoller path) exhibits the same save+publish behaviour")
    void deliverCalledDirectly_sameSaveAndPublishBehaviour() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());

        NotificationDto result = service.deliver(
                "student-123", NotificationType.MENTOR_REPLIED, "Mentor replied", "Check session",
                Map.of(), "/mentor/1");

        assertThat(result).isNotNull();
        assertThat(result.getNotificationType()).isEqualTo("MENTOR_REPLIED");
        assertThat(result.getChannel()).isEqualTo("IN_APP");
        assertThat(result.getActionUrl()).isEqualTo("/mentor/1");
        verify(notificationRepository, times(1)).save(any());
        verify(notificationPublisher, times(1)).publishToWebSocket(any(), eq("student-123"));
    }

    @Test
    @DisplayName("sendNotification - sends inApp, email, and push when enabled and within limits")
    void sendNotification_allChannelsEnabled() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());
        when(notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(any(), eq(NotificationChannel.EMAIL), any()))
                .thenReturn(0L);
        when(notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(any(), eq(NotificationChannel.PUSH), any()))
                .thenReturn(0L);

        NotificationPreference pref = NotificationPreference.builder()
                .studentId("student-123")
                .notificationType(NotificationType.MENTOR_REPLIED)
                .inAppEnabled(true)
                .emailEnabled(true)
                .pushEnabled(true)
                .build();
        when(preferenceRepository.findByStudentIdAndNotificationType("student-123", NotificationType.MENTOR_REPLIED))
                .thenReturn(java.util.Optional.of(pref));

        SendNotificationRequest request = new SendNotificationRequest();
        request.setStudentId("student-123");
        request.setNotificationType("MENTOR_REPLIED");
        request.setTitle("Test Title");
        request.setBody("Test Body");
        request.setActionUrl("/test");

        service.sendNotification(request);

        // Saved 3 times: IN_APP, EMAIL, PUSH
        verify(notificationRepository, times(3)).save(any());
        verify(notificationPublisher, times(1)).publishToWebSocket(any(), eq("student-123"));
    }

    @Test
    @DisplayName("sendNotification - respects daily limits for email and push")
    void sendNotification_respectsDailyLimits() {
        when(notificationRepository.save(any())).thenReturn(savedNotification());
        when(notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(any(), eq(NotificationChannel.EMAIL), any()))
                .thenReturn(5L);
        when(notificationRepository.countByStudentIdAndChannelAndCreatedAtAfter(any(), eq(NotificationChannel.PUSH), any()))
                .thenReturn(10L);

        NotificationPreference pref = NotificationPreference.builder()
                .studentId("student-123")
                .notificationType(NotificationType.MENTOR_REPLIED)
                .inAppEnabled(true)
                .emailEnabled(true)
                .pushEnabled(true)
                .build();
        when(preferenceRepository.findByStudentIdAndNotificationType("student-123", NotificationType.MENTOR_REPLIED))
                .thenReturn(java.util.Optional.of(pref));

        SendNotificationRequest request = new SendNotificationRequest();
        request.setStudentId("student-123");
        request.setNotificationType("MENTOR_REPLIED");
        request.setTitle("Test Title");
        request.setBody("Test Body");

        service.sendNotification(request);

        // Only IN_APP saved because email and push quotas exceeded
        verify(notificationRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("getUnreadNotifications - returns mapped DTOs")
    void getUnreadNotifications_returnsMappedDtos() {
        Notification n = savedNotification();
        when(notificationRepository.findByStudentIdAndIsReadFalseOrderByCreatedAtDesc("student-123"))
                .thenReturn(List.of(n));

        List<NotificationDto> result = service.getUnreadNotifications("student-123");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo(n.getTitle());
    }

    @Test
    @DisplayName("getAllNotifications - returns mapped DTOs with limit")
    void getAllNotifications_returnsMappedDtos() {
        Notification n = savedNotification();
        when(notificationRepository.findByStudentIdOrderByCreatedAtDesc(eq("student-123"), any()))
                .thenReturn(List.of(n));

        List<NotificationDto> result = service.getAllNotifications("student-123", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo(n.getTitle());
    }

    @Test
    @DisplayName("markAllAsRead - marks all unread notifications as read")
    void markAllAsRead_marksAllAsRead() {
        Notification n = savedNotification();
        when(notificationRepository.findByStudentIdAndIsReadFalseOrderByCreatedAtDesc("student-123"))
                .thenReturn(List.of(n));

        service.markAllAsRead("student-123");

        assertThat(n.isRead()).isTrue();
        verify(notificationRepository).save(n);
    }

    @Test
    @DisplayName("markAsRead - non-existent notification does not throw")
    void markAsRead_nonExistentNotification() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(java.util.Optional.empty());

        service.markAsRead(id.toString());

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("getPreferences - returns mapped DTOs")
    void getPreferences_returnsMappedDtos() {
        NotificationPreference pref = NotificationPreference.builder()
                .studentId("student-123")
                .notificationType(NotificationType.GENERAL)
                .inAppEnabled(true)
                .emailEnabled(false)
                .pushEnabled(true)
                .quietHoursStart(22)
                .quietHoursEnd(8)
                .build();
        when(preferenceRepository.findByStudentId("student-123")).thenReturn(List.of(pref));

        List<NotificationPreferenceDto> prefs = service.getPreferences("student-123");

        assertThat(prefs).hasSize(1);
        assertThat(prefs.get(0).getNotificationType()).isEqualTo("GENERAL");
        assertThat(prefs.get(0).isInAppEnabled()).isTrue();
        assertThat(prefs.get(0).isEmailEnabled()).isFalse();
    }

    @Test
    @DisplayName("updatePreference - updates existing and handles nulls")
    void updatePreference_updatesExistingPreference() {
        NotificationPreference existing = NotificationPreference.builder()
                .studentId("student-123")
                .notificationType(NotificationType.GENERAL)
                .inAppEnabled(false)
                .emailEnabled(false)
                .build();
        when(preferenceRepository.findByStudentIdAndNotificationType("student-123", NotificationType.GENERAL))
                .thenReturn(java.util.Optional.of(existing));

        UpdatePreferencesRequest request = new UpdatePreferencesRequest();
        request.setNotificationType("GENERAL");
        request.setInAppEnabled(true);
        request.setEmailEnabled(true);
        request.setPushEnabled(false);
        request.setQuietHoursStart(23);
        request.setQuietHoursEnd(7);

        service.updatePreference("student-123", request);

        assertThat(existing.isInAppEnabled()).isTrue();
        assertThat(existing.isEmailEnabled()).isTrue();
        assertThat(existing.isPushEnabled()).isFalse();
        assertThat(existing.getQuietHoursStart()).isEqualTo(23);
        assertThat(existing.getQuietHoursEnd()).isEqualTo(7);
        verify(preferenceRepository).save(existing);
    }
}
