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

    /** "SENT" on success, "SUPPRESSED" if recipient was suppressed. */
    private String status;

    /** True if request matched an existing idempotency record and was not re-sent. */
    private Boolean idempotencyHit;
}
