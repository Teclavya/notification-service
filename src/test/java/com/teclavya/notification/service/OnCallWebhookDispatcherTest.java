package com.teclavya.notification.service;

import com.teclavya.notification.config.OnCallProperties;
import com.teclavya.notification.dto.event.TicketLifecycleEventData;
import com.teclavya.notification.entities.OnCallDispatchAudit;
import com.teclavya.notification.repo.OnCallDispatchAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnCallWebhookDispatcherTest {

    @Mock
    private OnCallProperties onCallProperties;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private EmailService emailService;

    @Mock
    private OnCallDispatchAuditRepository auditRepository;

    @InjectMocks
    private OnCallWebhookDispatcher dispatcher;

    private TicketLifecycleEventData testData;

    @BeforeEach
    void setUp() {
        testData = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-1001")
                .ticketId("uuid-1001")
                .title("Database Deadlock on Checkout")
                .priority("P0")
                .category("TECHNICAL")
                .status("OPEN")
                .assignedAgentId(null)
                .escalated(true)
                .escalationReason("75% first response SLA reached without agent assignment")
                .countdownRemainingSeconds(225L)
                .build();
    }

    @Test
    @DisplayName("dispatchSlack success sends HTTP POST and records SUCCESS audit")
    void dispatchSlack_success() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("https://hooks.slack.com/services/T00/B00/X12345");
        when(restTemplate.postForEntity(eq("https://hooks.slack.com/services/T00/B00/X12345"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        boolean result = dispatcher.dispatchSlack(testData, "ticket.escalated");

        assertThat(result).isTrue();
        verify(restTemplate).postForEntity(eq("https://hooks.slack.com/services/T00/B00/X12345"), any(HttpEntity.class), eq(String.class));

        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getChannel()).isEqualTo("SLACK");
        assertThat(captor.getValue().getTicketNumber()).isEqualTo("TICK-1001");
    }

    @Test
    @DisplayName("dispatchSlack handles HTTP error gracefully and records FAILED audit")
    void dispatchSlack_httpFailure() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("https://hooks.slack.com/services/T00/B00/X12345");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("500 Internal Server Error"));

        boolean result = dispatcher.dispatchSlack(testData, "ticket.escalated");

        assertThat(result).isFalse();
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorMessage()).contains("500 Internal Server Error");
    }

    @Test
    @DisplayName("dispatchSlack skips dispatch if disabled or webhook URL is blank")
    void dispatchSlack_disabledOrBlank() {
        when(onCallProperties.isEnabled()).thenReturn(false);

        boolean result = dispatcher.dispatchSlack(testData, "ticket.escalated");

        assertThat(result).isFalse();
        verifyNoInteractions(restTemplate);
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("dispatchDiscord success sends HTTP POST and records SUCCESS audit")
    void dispatchDiscord_success() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/123/xyz");
        when(restTemplate.postForEntity(eq("https://discord.com/api/webhooks/123/xyz"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(""));

        boolean result = dispatcher.dispatchDiscord(testData, "ticket.escalated");

        assertThat(result).isTrue();
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getChannel()).isEqualTo("DISCORD");
    }

    @Test
    @DisplayName("dispatchDiscord handles error gracefully and records FAILED audit")
    void dispatchDiscord_failure() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/123/xyz");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Network timeout"));

        boolean result = dispatcher.dispatchDiscord(testData, "ticket.escalated");

        assertThat(result).isFalse();
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("dispatchDiscord skips dispatch if webhook is empty")
    void dispatchDiscord_emptyWebhook() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("");

        boolean result = dispatcher.dispatchDiscord(testData, "ticket.escalated");

        assertThat(result).isFalse();
        verifyNoInteractions(restTemplate);
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("dispatchAdminEmail sends alert email to on-call lead")
    void dispatchAdminEmail_success() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.isEmailAlertsEnabled()).thenReturn(true);
        when(onCallProperties.getAdminAlertEmail()).thenReturn("lead-oncall@teclavya.com");

        boolean result = dispatcher.dispatchAdminEmail(testData, "ticket.escalated");

        assertThat(result).isTrue();
        verify(emailService).sendEmail(eq("lead-oncall@teclavya.com"), contains("TICK-1001"), contains("Urgent Support Ticket Escalation"));
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
        assertThat(captor.getValue().getChannel()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("dispatchAdminEmail handles email exception gracefully")
    void dispatchAdminEmail_exception() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.isEmailAlertsEnabled()).thenReturn(true);
        when(onCallProperties.getAdminAlertEmail()).thenReturn("lead-oncall@teclavya.com");
        doThrow(new RuntimeException("SMTP send failed")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        boolean result = dispatcher.dispatchAdminEmail(testData, "ticket.escalated");

        assertThat(result).isFalse();
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorMessage()).contains("SMTP send failed");
    }

    @Test
    @DisplayName("dispatchSlack handles unexpected generic exception gracefully")
    void dispatchSlack_genericException() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("http://short");
        when(restTemplate.postForEntity(anyString(), any(), any())).thenThrow(new IllegalStateException("Unexpected state"));

        boolean result = dispatcher.dispatchSlack(testData, "ticket.escalated");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("dispatchDiscord handles unexpected generic exception gracefully")
    void dispatchDiscord_genericException() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("http://short");
        when(restTemplate.postForEntity(anyString(), any(), any())).thenThrow(new IllegalStateException("Unexpected state"));

        boolean result = dispatcher.dispatchDiscord(testData, "ticket.escalated");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("recordAudit handles database repository failure gracefully without throwing")
    void recordAudit_repoExceptionHandled() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("https://hooks.slack.com/services/T00/B00/X12345");
        when(restTemplate.postForEntity(anyString(), any(), any())).thenReturn(ResponseEntity.ok("ok"));
        doThrow(new RuntimeException("DB Connection down")).when(auditRepository).save(any());

        // Should complete successfully without failing caller
        boolean result = dispatcher.dispatchSlack(testData, "ticket.escalated");
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("dispatchAdminEmail skips when email alerts disabled")
    void dispatchAdminEmail_disabled() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.isEmailAlertsEnabled()).thenReturn(false);

        boolean result = dispatcher.dispatchAdminEmail(testData, "ticket.escalated");

        assertThat(result).isFalse();
        verifyNoInteractions(emailService);
        ArgumentCaptor<OnCallDispatchAudit> captor = ArgumentCaptor.forClass(OnCallDispatchAudit.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("hasConfiguredChannel returns false when master toggle is disabled")
    void hasConfiguredChannel_disabled() {
        when(onCallProperties.isEnabled()).thenReturn(false);
        assertThat(dispatcher.hasConfiguredChannel()).isFalse();
    }

    @Test
    @DisplayName("hasConfiguredChannel returns true when Slack webhook is configured")
    void hasConfiguredChannel_slackConfigured() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("https://hooks.slack.com/services/123");
        assertThat(dispatcher.hasConfiguredChannel()).isTrue();
    }

    @Test
    @DisplayName("hasConfiguredChannel returns true when Discord webhook is configured")
    void hasConfiguredChannel_discordConfigured() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("");
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("https://discord.com/api/webhooks/456");
        assertThat(dispatcher.hasConfiguredChannel()).isTrue();
    }

    @Test
    @DisplayName("hasConfiguredChannel returns true when Admin email is configured")
    void hasConfiguredChannel_emailConfigured() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("");
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("");
        when(onCallProperties.isEmailAlertsEnabled()).thenReturn(true);
        when(onCallProperties.getAdminAlertEmail()).thenReturn("lead@teclavya.com");
        assertThat(dispatcher.hasConfiguredChannel()).isTrue();
    }

    @Test
    @DisplayName("hasConfiguredChannel returns false when no channels are configured")
    void hasConfiguredChannel_noneConfigured() {
        when(onCallProperties.isEnabled()).thenReturn(true);
        when(onCallProperties.getSlackWebhookUrl()).thenReturn("");
        when(onCallProperties.getDiscordWebhookUrl()).thenReturn("");
        when(onCallProperties.isEmailAlertsEnabled()).thenReturn(false);
        assertThat(dispatcher.hasConfiguredChannel()).isFalse();
    }
}
