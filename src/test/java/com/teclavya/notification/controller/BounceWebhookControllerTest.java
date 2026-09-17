package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.dto.request.BounceWebhookRequest;
import com.teclavya.notification.service.SuppressionService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = BounceWebhookController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = { com.teclavya.notification.security.JwtAuthenticationFilter.class }
        )
)
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "notification.webhook.secret=test-webhook-secret"
})
class BounceWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SuppressionService suppressionService;

    @MockBean
    private com.teclavya.notification.security.JwtUtil jwtUtil;

    @Test
    @DisplayName("POST /webhook/bounce: missing or invalid secret returns 401 Unauthorized")
    void handleBounce_invalidSecret_returns401() throws Exception {
        BounceWebhookRequest request = BounceWebhookRequest.builder()
                .email("student@college.edu")
                .bounceType("HARD")
                .source("AWS_SES")
                .notes("550 User not found")
                .build();

        mockMvc.perform(post("/api/v1/notifications/webhook/bounce")
                        .header("X-Webhook-Secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(suppressionService);
    }

    @Test
    @DisplayName("POST /webhook/bounce: valid HARD bounce triggers suppression and returns 200 RECORDED")
    void handleBounce_hardBounce_suppressesAndReturns200() throws Exception {
        BounceWebhookRequest request = BounceWebhookRequest.builder()
                .email("bounced@college.edu")
                .bounceType("HARD")
                .source("SMTP_SERVER")
                .notes("550 No such user here")
                .build();

        mockMvc.perform(post("/api/v1/notifications/webhook/bounce")
                        .header("X-Webhook-Secret", "test-webhook-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"));

        verify(suppressionService).suppressEmail(
                eq("bounced@college.edu"),
                eq("HARD_BOUNCE"),
                eq("SMTP_SERVER"),
                eq("550 No such user here")
        );
    }

    @Test
    @DisplayName("POST /webhook/bounce: valid SOFT bounce returns 200 RECORDED without suppression")
    void handleBounce_softBounce_recordsWithoutSuppression() throws Exception {
        BounceWebhookRequest request = BounceWebhookRequest.builder()
                .email("mailboxfull@college.edu")
                .bounceType("SOFT")
                .source("SMTP_SERVER")
                .notes("452 Mailbox full")
                .build();

        mockMvc.perform(post("/api/v1/notifications/webhook/bounce")
                        .header("X-Webhook-Secret", "test-webhook-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECORDED"));

        verifyNoInteractions(suppressionService);
    }
}
