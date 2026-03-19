package com.teclavya.notification.service;

import com.teclavya.notification.dto.request.SendNotificationRequest;
import com.teclavya.notification.dto.request.UpdatePreferencesRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.dto.response.NotificationPreferenceDto;
import java.util.List;

public interface NotificationService {
    void sendNotification(SendNotificationRequest request);
    List<NotificationDto> getUnreadNotifications(String studentId);
    List<NotificationDto> getAllNotifications(String studentId, int limit);
    void markAsRead(String notificationId);
    void markAllAsRead(String studentId);
    List<NotificationPreferenceDto> getPreferences(String studentId);
    void updatePreference(String studentId, UpdatePreferencesRequest request);
}
