package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.config.TestSecurityConfig;
import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.security.JwtAuthenticationFilter;
import com.teclavya.notification.security.JwtUtil;
import com.teclavya.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = InternalNotificationController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = { JwtAuthenticationFilter.class }
    )
)
@Import(TestSecurityConfig.class)
class InternalNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private JwtUtil jwtUtil;

    private NotificationDto sampleDto() {
        return NotificationDto.builder()
                .notificationId("uuid-123")
                .notificationType("MENTOR_REPLIED")
                .channel("IN_APP")
                .title("Mentor replied")
                .body("Check your session")
                .actionUrl("/mentor/chat/session-1")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /internal/send - should return 200 with notification dto")
    void shouldSendInternalNotification() throws Exception {
        InternalSendRequest request = new InternalSendRequest(
                "mentor", "student-123", "MENTOR_REPLIED",
                "Mentor replied", "Check your session",
                Map.of(), "/mentor/chat/session-1");

        when(notificationService.sendNotificationInternal(any())).thenReturn(sampleDto());

        mockMvc.perform(post("/api/v1/notifications/internal/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationType").value("MENTOR_REPLIED"))
                .andExpect(jsonPath("$.channel").value("IN_APP"))
                .andExpect(jsonPath("$.actionUrl").value("/mentor/chat/session-1"))
                .andExpect(jsonPath("$.read").value(false));
    }

    @Test
    @DisplayName("POST /internal/send - missing studentId should return 400")
    void shouldReturn400WhenStudentIdMissing() throws Exception {
        InternalSendRequest request = new InternalSendRequest(
                "mentor", "", "MENTOR_REPLIED",
                "Mentor replied", "Check your session",
                Map.of(), "/mentor/chat/session-1");

        mockMvc.perform(post("/api/v1/notifications/internal/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /internal/send-batch - should return 200 with list")
    void shouldSendBatch() throws Exception {
        List<InternalSendRequest> requests = List.of(
                new InternalSendRequest("mentor", "student-123", "MENTOR_REPLIED",
                        "Title 1", "Body 1", Map.of(), "/mentor/1"),
                new InternalSendRequest("cohorts", "student-456", "COHORT_POST_REPLY",
                        "Title 2", "Body 2", Map.of(), "/cohorts/2")
        );

        when(notificationService.sendNotificationBatch(any()))
                .thenReturn(List.of(sampleDto(), sampleDto()));

        mockMvc.perform(post("/api/v1/notifications/internal/send-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requests)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}