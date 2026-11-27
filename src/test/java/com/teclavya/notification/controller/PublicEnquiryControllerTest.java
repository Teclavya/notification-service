package com.teclavya.notification.controller;

import com.teclavya.notification.dto.response.PublicEnquiryResponse;
import com.teclavya.notification.service.PublicEnquiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PublicEnquiryControllerTest {

    @Mock
    private PublicEnquiryService enquiryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicEnquiryController(enquiryService)).build();
    }

    @Test
    void submitEnquiry_validPayload_returns202() throws Exception {
        when(enquiryService.submit(any(), anyString())).thenReturn(
                PublicEnquiryResponse.builder()
                        .referenceId("ENQ-12345678")
                        .routedTo("support@teclavya.com")
                        .sla("Within 24 hours")
                        .build()
        );

        mockMvc.perform(post("/api/v1/notifications/public/enquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Aarav Mehta",
                                  "email": "aarav@example.com",
                                  "topic": "support",
                                  "message": "Need help accessing my cohort lab environment."
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.referenceId").value("ENQ-12345678"))
                .andExpect(jsonPath("$.routedTo").value("support@teclavya.com"));
    }

    @Test
    void submitEnquiry_invalidEmailOrUnknownTopic_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/public/enquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "A",
                                  "email": "not-an-email",
                                  "topic": "arbitrary@attacker.com",
                                  "message": "hi"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitEnquiry_rateLimitExceeded_returns429() throws Exception {
        when(enquiryService.submit(any(), anyString())).thenReturn(
                PublicEnquiryResponse.builder().referenceId("ENQ-1").routedTo("info@teclavya.com").sla("1d").build()
        );

        String body = """
                {
                  "fullName": "Rate Tester",
                  "email": "rate@example.com",
                  "topic": "info",
                  "message": "Testing sliding window rate limit protection."
                }
                """;

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/notifications/public/enquiries")
                            .header("X-Forwarded-For", "198.51.100.99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/api/v1/notifications/public/enquiries")
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void submitEnquiry_mailFailure_returns500() throws Exception {
        when(enquiryService.submit(any(), anyString())).thenThrow(new MailSendException("SMTP down"));

        mockMvc.perform(post("/api/v1/notifications/public/enquiries")
                        .header("X-Forwarded-For", "198.51.100.77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Failure Tester",
                                  "email": "fail@example.com",
                                  "topic": "support",
                                  "message": "Testing honest 500 error propagation on SMTP failure."
                                }
                                """))
                .andExpect(status().isInternalServerError());
    }
}
