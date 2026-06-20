package com.teclavya.notification.service;

/**
 * Outbound email dispatch service.
 * When JavaMailSender is not configured (spring.mail.host absent), implementations
 * must log the email and not throw — this is the dev/no-credentials fallback.
 */
public interface EmailService {

    /**
     * Send a plain HTML email.
     *
     * @param to       recipient address
     * @param subject  email subject
     * @param htmlBody HTML email body
     * @throws org.springframework.mail.MailException if the underlying transport fails
     *         (only thrown when JavaMailSender IS configured; callers convert to 500)
     */
    void sendEmail(String to, String subject, String htmlBody);
}
