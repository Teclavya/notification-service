package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.dto.request.SendEmailRequest;
import com.teclavya.notification.entities.EmailSendEvent;
import com.teclavya.notification.service.EmailSendAuditService;
import com.teclavya.notification.service.EmailService;
import com.teclavya.notification.service.NotificationService;
import com.teclavya.notification.service.SuppressionService;
import com.teclavya.notification.service.impl.EmailTemplateRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import com.teclavya.notification.config.TestSecurityConfig;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = NotificationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = { com.teclavya.notification.security.JwtAuthenticationFilter.class }
        )
)
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "internal.service.token=test-internal-token"
})
class NotificationControllerEmailHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private EmailService emailService;

    @MockBean
    private EmailTemplateRenderer emailTemplateRenderer;

    @MockBean
    private SuppressionService suppressionService;

    @MockBean
    private EmailSendAuditService emailSendAuditService;

    @MockBean
    private com.teclavya.notification.security.JwtUtil jwtUtil;

    private SendEmailRequest validRequest() {
        return SendEmailRequest.builder()
                .to("student@college.edu")
                .subject("Welcome to Cohort")
                .templateId("cohort-invite")
                .params(Map.of("cohortName", "CS 2026", "acceptUrl", "https://app.teclavya.com/join"))
                .idempotencyKey("idem-key-101")
                .build();
    }

    @Test
    @DisplayName("sendEmail: recipient suppressed returns 200 SUPPRESSED without calling emailService")
    void sendEmail_suppressedRecipient_returns200Suppressed() throws Exception {
        when(suppressionService.isSuppressed("student@college.edu")).thenReturn(true);

        mockMvc.perform(post("/api/v1/notifications/email")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"));

        verify(emailSendAuditService).recordSuppressed(eq("student@college.edu"), any(), any(), eq("idem-key-101"));
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("sendEmail: idempotency hit returns 202 with idempotencyHit=true without re-sending")
    void sendEmail_idempotencyHit_returns202WithoutResend() throws Exception {
        EmailSendEvent previous = EmailSendEvent.builder()
                .idempotencyKey("idem-key-101")
                .status("SENT")
                .build();
        when(emailSendAuditService.findByIdempotencyKey("idem-key-101")).thenReturn(Optional.of(previous));

        mockMvc.perform(post("/api/v1/notifications/email")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.idempotencyHit").value(true));

        verifyNoInteractions(emailService);
        verifyNoInteractions(suppressionService);
    }

    @Test
    @DisplayName("sendEmail: unsuppressed valid send dispatches to emailService and records audit")
    void sendEmail_unsuppressedValidSend_succeeds() throws Exception {
        when(emailSendAuditService.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(suppressionService.isSuppressed(any())).thenReturn(false);
        when(emailTemplateRenderer.render(eq("cohort-invite"), any())).thenReturn("<html>Welcome!</html>");

        EmailSendEvent pending = EmailSendEvent.builder().status("PENDING").build();
        when(emailSendAuditService.recordPending(any(), any(), any(), any())).thenReturn(pending);

        mockMvc.perform(post("/api/v1/notifications/email")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"));

        verify(emailService).sendEmail("student@college.edu", "Welcome to Cohort", "<html>Welcome!</html>");
        verify(emailSendAuditService).recordSent(pending, null);
    }

    @Test
    @DisplayName("sendEmail: MailException marks audit FAILED and returns 500")
    void sendEmail_mailException_returns500() throws Exception {
        when(emailSendAuditService.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(suppressionService.isSuppressed(any())).thenReturn(false);
        when(emailTemplateRenderer.render(any(), any())).thenReturn("<html>Welcome!</html>");

        EmailSendEvent pending = EmailSendEvent.builder().status("PENDING").build();
        when(emailSendAuditService.recordPending(any(), any(), any(), any())).thenReturn(pending);
        doThrow(new MailSendException("SMTP relay timeout")).when(emailService).sendEmail(any(), any(), any());

        mockMvc.perform(post("/api/v1/notifications/email")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isInternalServerError());

        verify(emailSendAuditService).recordFailed(eq(pending), contains("SMTP relay timeout"));
    }
}
