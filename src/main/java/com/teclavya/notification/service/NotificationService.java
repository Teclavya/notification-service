package com.teclavya.notification.service;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import com.teclavya.notification.entities.NotificationType;
import java.util.List;
import java.util.Map;

public interface NotificationService {
    void sendNotification(SendNotificationRequest request);
    NotificationDto sendNotificationInternal(InternalSendRequest request);

    /**
     * Delivery primitive (NS-BE-4b) — creates an IN_APP {@code Notification} row and publishes
     * it over the websocket. Extracted from {@link #sendNotificationInternal(InternalSendRequest)}
     * so {@code /send}/{@code /send-batch} and {@code LifecycleSendGatePoller} (learning-journey
     * ethical send-gate) share one delivery path — behaviour-preserving refactor (design §14b,
     * AC-9.3), proven by a parity test against the prior {@code sendNotificationInternal} behaviour.
     */
    NotificationDto deliver(String studentId, NotificationType type, String title, String body,
                             Map<String, Object> metadata, String actionUrl);
    List<NotificationDto> sendNotificationBatch(List<InternalSendRequest> requests);
    List<NotificationDto> getUnreadNotifications(String studentId);
    List<NotificationDto> getAllNotifications(String studentId, int limit);
    void markAsRead(String notificationId);
    void markAllAsRead(String studentId);
    long getUnreadCount(String studentId);
    void deleteNotification(String notificationId);
    List<NotificationPreferenceDto> getPreferences(String studentId);
    void updatePreference(String studentId, UpdatePreferencesRequest request);
}