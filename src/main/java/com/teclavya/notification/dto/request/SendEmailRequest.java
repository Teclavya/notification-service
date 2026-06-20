package com.teclavya.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Map;

/**
 * Request body for POST /api/v1/notifications/email.
 * Internal service-to-service contract — called by cohorts-latest for cohort invite emails.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SendEmailRequest {

    /** Recipient email address. Must be a well-formed email address. */
    @NotBlank
    @Email
    private String to;

    /** Email subject line. */
    @NotBlank
    private String subject;

    /**
     * Template identifier — must match a file under {@code classpath:templates/}.
     * Use {@code "cohort-invite"} for the cohort invitation email
     * (resolves to {@code templates/cohort-invite.html}).
     */
    @NotBlank
    private String templateId;

    /**
     * Template parameters substituted into the template body.
     * For {@code "cohort-invite"}: expects keys {@code cohortName} and {@code acceptUrl}.
     */
    private Map<String, String> params;
}
