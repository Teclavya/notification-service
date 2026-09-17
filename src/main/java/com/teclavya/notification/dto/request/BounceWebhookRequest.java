package com.teclavya.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BounceWebhookRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String bounceType;

    private String source;

    private String notes;
}
