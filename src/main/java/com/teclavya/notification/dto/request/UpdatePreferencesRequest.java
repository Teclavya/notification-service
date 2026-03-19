package com.teclavya.notification.dto.request;

import lombok.*;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class UpdatePreferencesRequest {
    private String notificationType;
    private Boolean inAppEnabled;
    private Boolean emailEnabled;
    private Boolean pushEnabled;
    private Integer quietHoursStart;
    private Integer quietHoursEnd;
}
