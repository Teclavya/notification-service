package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.config.TestSecurityConfig;
import com.teclavya.notification.dto.request.InternalSendRequest;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
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
import java.util.UUID;

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
    private LifecycleQueueService lifecycleQueueService;

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

    // -----------------------------------------------------------------------
    // POST /internal/send-gated (NS-BE-1)
    // -----------------------------------------------------------------------

    private LifecycleMessageReviewQueue draftedRow(UUID id) {
        return LifecycleMessageReviewQueue.builder()
                .id(id)
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .messageBody("Your milestone is due soon")
                .status(LifecycleMessageStatus.DRAFTED)
                .tier("AMBER")
                .build();
    }

    @Test
    @DisplayName("POST /internal/send-gated - valid request returns 202 with DRAFTED status")
    void shouldReturn202AndDraftedOnValidGatedSend() throws Exception {
        UUID queueId = UUID.randomUUID();
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .title("Your milestone is due soon")
                .body("Hey — your milestone is due in 2 days.")
                .metadata(Map.of("tier", "AMBER", "timezone", "Asia/Kolkata"))
                .actionUrl("/learn/path/1/module/3")
                .build();

        when(lifecycleQueueService.enqueue(any())).thenReturn(draftedRow(queueId));

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queueId").value(queueId.toString()))
                .andExpect(jsonPath("$.status").value("DRAFTED"));
    }

    @Test
    @DisplayName("POST /internal/send-gated - blank sourceService returns 400")
    void shouldReturn400WhenGatedSourceServiceBlank() throws Exception {
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("")
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .title("Title")
                .body("Body")
                .build();

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /internal/send-gated - blank studentId returns 400")
    void shouldReturn400WhenGatedStudentIdBlank() throws Exception {
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("")
                .notificationType("MILESTONE_DUE_SOON")
                .title("Title")
                .body("Body")
                .build();

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /internal/send-gated - blank title returns 400")
    void shouldReturn400WhenGatedTitleBlank() throws Exception {
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .title("")
                .body("Body")
                .build();

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /internal/send-gated - null notificationType returns 400")
    void shouldReturn400WhenGatedNotificationTypeNull() throws Exception {
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("student-1")
                .notificationType(null)
                .title("Title")
                .body("Body")
                .build();

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /internal/send-gated - persistence failure returns 500")
    void shouldReturn500WhenGatedEnqueueThrows() throws Exception {
        InternalSendRequest request = InternalSendRequest.builder()
                .sourceService("learning-progress-tracker")
                .studentId("student-1")
                .notificationType("MILESTONE_DUE_SOON")
                .title("Title")
                .body("Body")
                .build();

        when(lifecycleQueueService.enqueue(any()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/api/v1/notifications/internal/send-gated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("AC-9.3 regression: /send and /send-batch remain unaffected by send-gated addition")
    void existingSendEndpointsStillWorkUnaffected() throws Exception {
        InternalSendRequest request = new InternalSendRequest(
                "mentor", "student-123", "MENTOR_REPLIED",
                "Mentor replied", "Check your session",
                Map.of(), "/mentor/chat/session-1");

        when(notificationService.sendNotificationInternal(any())).thenReturn(sampleDto());

        mockMvc.perform(post("/api/v1/notifications/internal/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationType").value("MENTOR_REPLIED"));
    }
}