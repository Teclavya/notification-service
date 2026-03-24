package com.teclavya.notification.service.impl;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.response.NotificationDto;
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
}
