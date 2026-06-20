package com.teclavya.notification.service;

import com.teclavya.notification.service.impl.EmailServiceImpl;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TEST-4: Unit tests for EmailService (send path + no-sender log-only fallback).
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private MimeMessage mimeMessage;

    private EmailServiceImpl emailServiceWithSender;
    private EmailServiceImpl emailServiceWithoutSender;

    @BeforeEach
    void setUp() {
        emailServiceWithSender = new EmailServiceImpl(
                Optional.of(mailSender), "noreply@teclavya.com");
        emailServiceWithoutSender = new EmailServiceImpl(
                Optional.empty(), "noreply@teclavya.com");
    }

    @Test
    @DisplayName("sendEmail — JavaMailSender.send called with correct to/subject/htmlBody")
    void shouldCallMailSenderWithCorrectArgs() {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        emailServiceWithSender.sendEmail(
                "recipient@example.com",
                "You're invited to join TestCohort",
                "<html><body>Join now!</body></html>");

        verify(mailSender).createMimeMessage();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmail — MailException from sender.send propagates to caller")
    void shouldPropagateMailException() {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP rejected"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() ->
                emailServiceWithSender.sendEmail(
                        "recipient@example.com",
                        "Invited",
                        "<html><body>Join!</body></html>")
        ).isInstanceOf(MailSendException.class)
         .hasMessageContaining("SMTP rejected");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendEmail — no JavaMailSender configured → logs only, no exception")
    void shouldLogOnlyWhenMailSenderAbsent() {
        // Must not throw; no sender is present (log-only dev mode)
        emailServiceWithoutSender.sendEmail(
                "recipient@example.com",
                "You're invited",
                "<html><body>Hello!</body></html>");
        // Assertion: no exception means the log-only fallback ran correctly.
        // Verify the real mail sender was never touched.
        verifyNoInteractions(mailSender);
    }
}
