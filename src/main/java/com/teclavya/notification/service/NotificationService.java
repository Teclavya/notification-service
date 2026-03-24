package com.teclavya.notification.service;

import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import java.util.List;

public interface NotificationService {
    void sendNotification(SendNotificationRequest request);
    NotificationDto sendNotificationInternal(InternalSendRequest request);
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