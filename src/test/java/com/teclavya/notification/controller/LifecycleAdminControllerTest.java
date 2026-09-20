package com.teclavya.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teclavya.notification.dto.request.EditMessageBodyRequest;
import com.teclavya.notification.dto.request.VetoMessageRequest;
import com.teclavya.notification.dto.response.AutonomyMetricsResponse;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageReviewQueue;
import com.teclavya.notification.lifecycle.entity.LifecycleMessageStatus;
import com.teclavya.notification.lifecycle.repo.LifecycleMessageReviewQueueRepository;
import com.teclavya.notification.lifecycle.service.AutonomyTierService;
import com.teclavya.notification.lifecycle.service.LifecycleQueueService;
import com.teclavya.notification.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LifecycleAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class LifecycleAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LifecycleQueueService lifecycleQueueService;

    @MockBean
    private LifecycleMessageReviewQueueRepository repository;

    @MockBean
    private AutonomyTierService autonomyTierService;

    @MockBean
    private JwtUtil jwtUtil;

    private LifecycleMessageReviewQueue sampleQueueRow(UUID id, LifecycleMessageStatus status) {
        return LifecycleMessageReviewQueue.builder()
                .id(id)
                .studentId("student-123")
                .notificationType("IDLE_7D")
                .messageBody("It's been a week since your last lesson.")
                .tier("AMBER")
                .status(status)
                .safetyVerdict("PASS")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/notifications/admin/lifecycle-queue - should list all rows when status not provided")
    void shouldListAllRows() throws Exception {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = sampleQueueRow(id, LifecycleMessageStatus.AWAITING_VETO_WINDOW);
        when(repository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/notifications/admin/lifecycle-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].notificationType").value("IDLE_7D"))
                .andExpect(jsonPath("$.content[0].status").value("AWAITING_VETO_WINDOW"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/admin/lifecycle-queue?status=DRAFTED - should filter by status")
    void shouldFilterByStatus() throws Exception {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = sampleQueueRow(id, LifecycleMessageStatus.DRAFTED);
        when(lifecycleQueueService.listForAdmin(eq(LifecycleMessageStatus.DRAFTED), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/notifications/admin/lifecycle-queue")
                        .param("status", "DRAFTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].status").value("DRAFTED"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/admin/lifecycle-queue/{id}/approve - should approve message")
    void shouldApproveMessage() throws Exception {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = sampleQueueRow(id, LifecycleMessageStatus.APPROVED);
        when(lifecycleQueueService.approve(id)).thenReturn(row);

        mockMvc.perform(post("/api/v1/notifications/admin/lifecycle-queue/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/admin/lifecycle-queue/{id}/veto - should veto message with reason")
    void shouldVetoMessage() throws Exception {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = sampleQueueRow(id, LifecycleMessageStatus.VETOED);
        row.setVetoReason("Inappropriate tone");
        when(lifecycleQueueService.veto(eq(id), eq("Inappropriate tone"))).thenReturn(row);

        VetoMessageRequest request = new VetoMessageRequest("Inappropriate tone");

        mockMvc.perform(post("/api/v1/notifications/admin/lifecycle-queue/{id}/veto", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VETOED"))
                .andExpect(jsonPath("$.vetoReason").value("Inappropriate tone"));
    }

    @Test
    @DisplayName("POST /api/v1/notifications/admin/lifecycle-queue/{id}/veto - blank reason should return 400")
    void shouldRejectBlankVetoReason() throws Exception {
        UUID id = UUID.randomUUID();
        VetoMessageRequest request = new VetoMessageRequest("   ");

        mockMvc.perform(post("/api/v1/notifications/admin/lifecycle-queue/{id}/veto", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/v1/notifications/admin/lifecycle-queue/{id}/body - should edit body")
    void shouldEditBody() throws Exception {
        UUID id = UUID.randomUUID();
        LifecycleMessageReviewQueue row = sampleQueueRow(id, LifecycleMessageStatus.DRAFTED);
        row.setMessageBody("Revised body text");
        when(lifecycleQueueService.editBody(eq(id), eq("Revised body text"))).thenReturn(row);

        EditMessageBodyRequest request = new EditMessageBodyRequest("Revised body text");

        mockMvc.perform(put("/api/v1/notifications/admin/lifecycle-queue/{id}/body", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageBody").value("Revised body text"));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/admin/lifecycle-queue/autonomy-metrics - should return metrics")
    void shouldReturnAutonomyMetrics() throws Exception {
        AutonomyMetricsResponse response = AutonomyMetricsResponse.builder()
                .currentTier("AMBER")
                .consecutiveCleanDays(12)
                .daysToGraduation(18)
                .totalEvaluated(150)
                .totalVetoed(1)
                .graduated(false)
                .build();

        when(autonomyTierService.calculateMetrics()).thenReturn(response);

        mockMvc.perform(get("/api/v1/notifications/admin/lifecycle-queue/autonomy-metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTier").value("AMBER"))
                .andExpect(jsonPath("$.consecutiveCleanDays").value(12))
                .andExpect(jsonPath("$.daysToGraduation").value(18))
                .andExpect(jsonPath("$.totalEvaluated").value(150))
                .andExpect(jsonPath("$.graduated").value(false));
    }
}
