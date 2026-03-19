package com.teclavya.notification.dto.response;

import lombok.*;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class NotificationPreferenceDto {
    private String notificationType;
    private boolean inAppEnabled;
    private boolean emailEnabled;
    private boolean pushEnabled;
    private Integer quietHoursStart;
    private Integer quietHoursEnd;
}
