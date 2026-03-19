package com.teclavya.notification.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.Map;

@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class SendNotificationRequest {
    @NotBlank
    private String studentId;
    @NotNull
    private String notificationType;
    @NotBlank
    private String title;
    private String body;
    private Map<String, Object> metadata;
}
