package com.teclavya.notification.service.impl;

import com.teclavya.notification.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;

/**
 * EmailService implementation.
 *
 * <p>If {@link JavaMailSender} is absent (spring.mail.host not set), this service
 * operates in log-only mode: emails are printed to the log at WARN level instead
 * of being dispatched.  This is the dev / no-credentials fallback per design §BE-5.
 *
 * <p>If {@link JavaMailSender} is present, {@link MailException} propagates to the
 * caller so the controller can convert it to a 500 response.
 */
@Service
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final Optional<JavaMailSender> mailSender;
    private final String fromAddress;
    private final boolean forceLogOnly;

    public EmailServiceImpl(
            Optional<JavaMailSender> mailSender,
            @Value("${spring.mail.username:noreply@teclavya.com}") String fromAddress,
            @Value("${notification.email.force-log-only:false}") boolean forceLogOnly,
            Environment environment) {
        this.mailSender = mailSender;
        this.forceLogOnly = forceLogOnly || Arrays.asList(environment.getActiveProfiles()).contains("dev");
        // Guard: if the configured value is blank (username not set), fall back to a safe default
        this.fromAddress = (fromAddress == null || fromAddress.isBlank())
                ? "noreply@teclavya.com"
                : fromAddress;
    }

    @Override
    public void sendEmail(String to, String subject, String htmlBody) {
        if (forceLogOnly || mailSender.isEmpty()) {
            log.warn("[EMAIL LOG-ONLY] to='{}' subject='{}' — JavaMailSender not configured; "
                    + "set spring.mail.host to enable outbound dispatch", to, subject);
            return;
        }

        JavaMailSender sender = mailSender.get();
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            sender.send(message);
            log.info("Email sent to '{}' with subject '{}'", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to build MIME message to '{}': {}", to, e.getMessage());
            throw new MailPreparationException("Failed to build MIME message", e);
        }
        // MailException from sender.send() propagates to caller as-is
    }
}
