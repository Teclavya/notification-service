package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.config.TestSecurityConfig;
import com.teclavya.notification.dto.request.SendEmailRequest;
import com.teclavya.notification.dto.response.SendEmailResponse;
import com.teclavya.notification.security.JwtAuthenticationFilter;
import com.teclavya.notification.security.JwtUtil;
import com.teclavya.notification.service.EmailService;
import com.teclavya.notification.service.NotificationService;
import com.teclavya.notification.service.impl.EmailTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TEST-4: @WebMvcTest for the POST /api/v1/notifications/email endpoint.
 *
 * Verifies:
 *  1. Valid request + correct internal token → 202 + { "status": "SENT" }
 *  2. Missing / wrong internal token → 401
 *  3. JavaMailSender throwing MailException → 500
 */
@WebMvcTest(
    controllers = NotificationController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = { JwtAuthenticationFilter.class }
    )
)
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "internal.service.token=test-secret-token-123")
class NotificationControllerEmailTest {

    private static final String VALID_TOKEN = "test-secret-token-123";
    private static final String ENDPOINT = "/api/v1/notifications/email";

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
    private JwtUtil jwtUtil;

    private SendEmailRequest validRequest() {
        return SendEmailRequest.builder()
                .to("friend@example.com")
                .subject("You're invited to join TestCohort cohort on Teclavya")
                .templateId("cohort-invite-email")
                .params(Map.of(
                        "cohortName", "TestCohort",
                        "acceptUrl", "https://teclavya.com/cohorts/invitations/abc123/accept"
                ))
                .build();
    }

    @BeforeEach
    void setUpTemplateRenderer() {
        when(emailTemplateRenderer.render(anyString(), anyMap()))
                .thenReturn("<html><body>You're invited!</body></html>");
    }

    @Test
    @DisplayName("POST /email — valid body + correct X-Internal-Token → 202 { status: SENT }")
    void shouldReturn202WhenTokenValidAndEmailSent() throws Exception {
        doNothing().when(emailService).sendEmail(any(), any(), any());

        mockMvc.perform(post(ENDPOINT)
                .header("X-Internal-Token", VALID_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"));

        verify(emailService).sendEmail(
                eq("friend@example.com"),
                eq("You're invited to join TestCohort cohort on Teclavya"),
                anyString());
    }

    @Test
    @DisplayName("POST /email — missing X-Internal-Token → 401")
    void shouldReturn401WhenTokenMissing() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("POST /email — wrong X-Internal-Token → 401")
    void shouldReturn401WhenTokenWrong() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                .header("X-Internal-Token", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("POST /email — JavaMailSender throws MailException → 500")
    void shouldReturn500WhenMailSenderFails() throws Exception {
        doThrow(new MailSendException("SMTP connection refused"))
                .when(emailService).sendEmail(any(), any(), any());

        mockMvc.perform(post(ENDPOINT)
                .header("X-Internal-Token", VALID_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("POST /email — missing required field 'to' → 400")
    void shouldReturn400WhenBodyInvalid() throws Exception {
        SendEmailRequest badRequest = SendEmailRequest.builder()
                .to("")           // @NotBlank violation
                .subject("Subject")
                .templateId("cohort-invite-email")
                .build();

        mockMvc.perform(post(ENDPOINT)
                .header("X-Internal-Token", VALID_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailService);
    }
}
