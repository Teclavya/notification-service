package com.teclavya.notification.dto.request;

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

    /** Recipient email address. */
    @NotBlank
    private String to;

    /** Email subject line. */
    @NotBlank
    private String subject;

    /**
     * Template identifier (e.g. "cohort-invite").
     * Used to select and render the appropriate HTML template.
     */
    @NotBlank
    private String templateId;

    /**
     * Template parameters substituted into the template body.
     * For "cohort-invite": expects keys {@code cohortName} and {@code acceptUrl}.
     */
    private Map<String, String> params;
}
