package com.teclavya.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Optional;

/**
 * Validates at startup that a real SMTP transport is configured for staging and prod environments.
 * Prevents silent log-only email drops on deployment (Ticket #1309 / G23).
 */
@Component
@Slf4j
public class MailStartupValidator implements ApplicationListener<ApplicationReadyEvent> {

    private final Optional<JavaMailSender> mailSender;
    private final Environment environment;
    private final String mailHost;

    public MailStartupValidator(
            Optional<JavaMailSender> mailSender,
            Environment environment,
            @Value("${spring.mail.host:}") String mailHost) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.mailHost = mailHost;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        validateMailConfiguration();
    }

    public void validateMailConfiguration() {
        String[] profiles = environment.getActiveProfiles();
        boolean isStrictProfile = Arrays.stream(profiles)
                .anyMatch(p -> p.equalsIgnoreCase("staging")
                        || p.equalsIgnoreCase("prod")
                        || p.equalsIgnoreCase("production"));

        boolean isMailConfigured = mailSender.isPresent() && StringUtils.hasText(mailHost);

        if (isStrictProfile && !isMailConfigured) {
            log.error("mail_not_configured: Profile(s) {} require a real SMTP transport (spring.mail.host). " +
                    "Failing loud at startup.", Arrays.toString(profiles));
            throw new IllegalStateException("CRITICAL: spring.mail.host is required in staging/prod profiles but was not configured!");
        }

        if (!isMailConfigured) {
            log.warn("mail_log_only_mode: JavaMailSender not configured. Email will operate in log-only mode for non-production profile(s): {}",
                    Arrays.toString(profiles));
        } else {
            log.info("mail_transport_ready: JavaMailSender verified for host '{}'", mailHost);
        }
    }
}
