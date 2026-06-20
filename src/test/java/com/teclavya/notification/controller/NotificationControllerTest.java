package com.teclavya.notification.controller;

import com.teclavya.notification.config.TestSecurityConfig;
import com.teclavya.notification.dto.response.NotificationDto;
import com.teclavya.notification.security.JwtAuthenticationFilter;
import com.teclavya.notification.security.JwtUtil;
import com.teclavya.notification.service.EmailService;
import com.teclavya.notification.service.NotificationService;
import com.teclavya.notification.service.impl.EmailTemplateRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = NotificationController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = { JwtAuthenticationFilter.class }
    )
)
@Import(TestSecurityConfig.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private EmailService emailService;

    @MockBean
    private EmailTemplateRenderer emailTemplateRenderer;

    @MockBean
    private JwtUtil jwtUtil;

    private NotificationDto sampleDto() {
        return NotificationDto.builder()
                .notificationId("uuid-123")
                .notificationType("MENTOR_REPLIED")
                .channel("IN_APP")
                .title("Test notification")
                .body("Test body")
                .actionUrl("/mentor/chat/1")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("GET /unread-count - should return count")
    void shouldReturnUnreadCount() throws Exception {
        when(notificationService.getUnreadCount("student-123")).thenReturn(5L);

        mockMvc.perform(get("/api/v1/notifications/student-123/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5));
    }

    @Test
    @DisplayName("GET /unread - should return list")
    void shouldReturnUnreadNotifications() throws Exception {
        when(notificationService.getUnreadNotifications("student-123"))
                .thenReturn(List.of(sampleDto()));

        mockMvc.perform(get("/api/v1/notifications/student-123/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actionUrl").value("/mentor/chat/1"));
    }

    @Test
    @DisplayName("DELETE /{id} - should return 204")
    void shouldDeleteNotification() throws Exception {
        mockMvc.perform(delete("/api/v1/notifications/uuid-123"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /{studentId} - should return all notifications")
    void shouldReturnAllNotifications() throws Exception {
        when(notificationService.getAllNotifications("student-123", 50))
                .thenReturn(List.of(sampleDto()));

        mockMvc.perform(get("/api/v1/notifications/student-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /{id}/read - should return 200")
    void shouldMarkAsRead() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/uuid-123/read"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /{studentId}/read-all - should return 200")
    void shouldMarkAllAsRead() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/student-123/read-all"))
                .andExpect(status().isOk());
    }
}