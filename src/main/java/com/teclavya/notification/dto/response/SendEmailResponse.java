package com.teclavya.notification.dto.response;

import lombok.*;

/**
 * Response body for POST /api/v1/notifications/email.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SendEmailResponse {

    /** "SENT" on success. */
    private String status;
}
