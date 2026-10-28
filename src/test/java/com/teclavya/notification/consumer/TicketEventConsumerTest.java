package com.teclavya.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teclavya.notification.dto.event.CloudEvent;
import com.teclavya.notification.dto.event.TicketLifecycleEventData;
import com.teclavya.notification.entities.ProcessedTicketEvent;
import com.teclavya.notification.repo.ProcessedTicketEventRepository;
import com.teclavya.notification.service.EmailService;
import com.teclavya.notification.service.OnCallWebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketEventConsumerTest {

    @Mock
    private ProcessedTicketEventRepository processedEventRepository;

    @Mock
    private OnCallWebhookDispatcher onCallWebhookDispatcher;

    @Mock
    private EmailService emailService;

    private ObjectMapper objectMapper;
    private TicketEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new TicketEventConsumer(
                processedEventRepository,
                onCallWebhookDispatcher,
                emailService,
                objectMapper
        );
    }

    @Test
    @DisplayName("ticket.created sends confirmation email to student and records idempotency")
    void processEvent_ticketCreated() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-101")
                .title("Cannot submit assignment")
                .priority("P1")
                .studentEmail("student@example.com")
                .status("NEW")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-101")
                .type("ticket.created")
                .data(data)
                .time(Instant.now())
                .build();

        when(processedEventRepository.existsByEventId("evt-101")).thenReturn(false);

        consumer.processEvent(event);

        verify(emailService).sendEmail(eq("student@example.com"), contains("TICK-101"), contains("Cannot submit assignment"));
        ArgumentCaptor<ProcessedTicketEvent> captor = ArgumentCaptor.forClass(ProcessedTicketEvent.class);
        verify(processedEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-101");
        assertThat(captor.getValue().getEventType()).isEqualTo("ticket.created");
        assertThat(captor.getValue().getTicketNumber()).isEqualTo("TICK-101");
    }

    @Test
    @DisplayName("ticket.escalated invokes multi-channel on-call dispatcher (Slack, Discord, Email)")
    void processEvent_ticketEscalated() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-102")
                .title("Production Sandbox Crash")
                .priority("P0")
                .status("NEW")
                .escalated(true)
                .escalationReason("75% first response SLA breached without assignment")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-102")
                .type("ticket.escalated")
                .data(data)
                .time(Instant.now())
                .build();

        when(processedEventRepository.existsByEventId("evt-102")).thenReturn(false);

        consumer.processEvent(event);

        verify(onCallWebhookDispatcher).dispatchSlack(data, "ticket.escalated");
        verify(onCallWebhookDispatcher).dispatchDiscord(data, "ticket.escalated");
        verify(onCallWebhookDispatcher).dispatchAdminEmail(data, "ticket.escalated");
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("ticket.resolved sends resolution email to student")
    void processEvent_ticketResolved() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-103")
                .title("Login error")
                .priority("P2")
                .studentEmail("student@example.com")
                .status("RESOLVED")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-103")
                .type("ticket.resolved")
                .data(data)
                .time(Instant.now())
                .build();

        when(processedEventRepository.existsByEventId("evt-103")).thenReturn(false);

        consumer.processEvent(event);

        verify(emailService).sendEmail(eq("student@example.com"), contains("TICK-103"), contains("marked as resolved"));
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("Duplicate event is skipped idempotently without duplicate side-effects")
    void processEvent_idempotentDuplicate() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-104")
                .title("Duplicate test")
                .studentEmail("student@example.com")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-duplicate")
                .type("ticket.created")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-duplicate")).thenReturn(true);

        consumer.processEvent(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(onCallWebhookDispatcher);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("handleTicketEvent successfully deserializes JSON string and processes event")
    void handleTicketEvent_rawJson() throws Exception {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-105")
                .title("Valid JSON String Test")
                .priority("P3")
                .studentEmail("test@example.com")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-105")
                .type("ticket.created")
                .data(data)
                .time(Instant.now())
                .build();

        String rawJson = objectMapper.writeValueAsString(event);
        when(processedEventRepository.existsByEventId("evt-105")).thenReturn(false);

        consumer.handleTicketEvent(rawJson);

        verify(emailService).sendEmail(eq("test@example.com"), anyString(), anyString());
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("handleTicketEvent throws AmqpRejectAndDontRequeueException on invalid JSON to route to DLQ")
    void handleTicketEvent_malformedJson() {
        assertThatThrownBy(() -> consumer.handleTicketEvent("not-valid-json"))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("handleTicketCreated and handleTicketResolved propagate email exceptions and do NOT record ledger")
    void processEvent_emailExceptionPropagatesAndDoesNotMarkProcessed() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-106")
                .studentEmail("student@example.com")
                .build();

        CloudEvent<TicketLifecycleEventData> createdEvt = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-106")
                .type("ticket.created")
                .data(data)
                .build();

        doThrow(new RuntimeException("Mail server down")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> consumer.processEvent(createdEvt))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Mail server down");

        // Verify ledger was NOT marked as processed on failure so retry can occur
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("ticket.escalated throws exception for retry when all configured on-call channels fail")
    void processEvent_escalationRetryOnAllChannelsFailed() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-107")
                .title("Urgent Outage")
                .priority("P0")
                .build();

        CloudEvent<TicketLifecycleEventData> evt = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-107")
                .type("ticket.escalated")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-107")).thenReturn(false);
        when(onCallWebhookDispatcher.hasConfiguredChannel()).thenReturn(true);
        when(onCallWebhookDispatcher.dispatchSlack(any(), any())).thenReturn(false);
        when(onCallWebhookDispatcher.dispatchDiscord(any(), any())).thenReturn(false);
        when(onCallWebhookDispatcher.dispatchAdminEmail(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> consumer.processEvent(evt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("All configured on-call channels failed");

        // Ledger must not be recorded so message can be retried
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("ticket.escalated records ledger when at least one configured channel succeeds")
    void processEvent_escalationSuccessOneChannel() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-108")
                .title("Urgent Outage")
                .priority("P0")
                .build();

        CloudEvent<TicketLifecycleEventData> evt = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-108")
                .type("ticket.escalated")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-108")).thenReturn(false);
        when(onCallWebhookDispatcher.hasConfiguredChannel()).thenReturn(true);
        when(onCallWebhookDispatcher.dispatchSlack(any(), any())).thenReturn(true);
        when(onCallWebhookDispatcher.dispatchDiscord(any(), any())).thenReturn(false);
        when(onCallWebhookDispatcher.dispatchAdminEmail(any(), any())).thenReturn(false);

        consumer.processEvent(evt);

        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("processEvent rejects null or malformed CloudEvent to DLQ")
    void processEvent_nullGuardsRejectToDlq() {
        assertThatThrownBy(() -> consumer.processEvent(null))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.processEvent(CloudEvent.<TicketLifecycleEventData>builder().id(null).build()))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);

        assertThatThrownBy(() -> consumer.processEvent(CloudEvent.<TicketLifecycleEventData>builder().id("1").type("ticket.created").data(null).build()))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(emailService);
        verifyNoInteractions(onCallWebhookDispatcher);
    }

    @Test
    @DisplayName("Producer event format (userEmail, subject, userId) is deserialized seamlessly by Consumer")
    void processEvent_producerContractCompatibility() throws Exception {
        String producerJson = "{\n" +
                "  \"id\": \"evt-producer-1\",\n" +
                "  \"source\": \"com.teclavya.adminserv\",\n" +
                "  \"type\": \"ticket.created\",\n" +
                "  \"datacontenttype\": \"application/json\",\n" +
                "  \"time\": \"2026-10-29T10:00:00Z\",\n" +
                "  \"data\": {\n" +
                "    \"ticketNumber\": \"TICK-PROD-99\",\n" +
                "    \"ticketId\": \"00000000-0000-0000-0000-000000000099\",\n" +
                "    \"userId\": \"00000000-0000-0000-0000-000000000001\",\n" +
                "    \"userEmail\": \"customer@example.com\",\n" +
                "    \"subject\": \"Cannot checkout cart\",\n" +
                "    \"priority\": \"P1\",\n" +
                "    \"status\": \"NEW\",\n" +
                "    \"isEscalated\": false,\n" +
                "    \"countdownRemainingSeconds\": 1800\n" +
                "  }\n" +
                "}";

        when(processedEventRepository.existsByEventId("evt-producer-1")).thenReturn(false);

        consumer.handleTicketEvent(producerJson);

        verify(emailService).sendEmail(
                eq("customer@example.com"),
                contains("Cannot checkout cart"),
                contains("Cannot checkout cart")
        );
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("Producer escalation event format (isEscalated, subject, countdownRemainingSeconds) is dispatched to on-call")
    void processEvent_producerEscalationContractCompatibility() throws Exception {
        String producerEscalationJson = "{\n" +
                "  \"id\": \"sla-esc-TICK-PROD-100-assigned\",\n" +
                "  \"source\": \"com.teclavya.adminserv\",\n" +
                "  \"type\": \"ticket.escalated\",\n" +
                "  \"datacontenttype\": \"application/json\",\n" +
                "  \"time\": \"2026-10-29T10:00:00Z\",\n" +
                "  \"data\": {\n" +
                "    \"ticketNumber\": \"TICK-PROD-100\",\n" +
                "    \"ticketId\": \"00000000-0000-0000-0000-000000000100\",\n" +
                "    \"subject\": \"Database deadlock on payment gateway\",\n" +
                "    \"priority\": \"P0\",\n" +
                "    \"status\": \"ASSIGNED\",\n" +
                "    \"assignedAgentId\": \"agent-42\",\n" +
                "    \"isEscalated\": true,\n" +
                "    \"escalationReason\": \"Resolution SLA breached. Escalated to Engineering Lead / On-Call SRE.\",\n" +
                "    \"countdownRemainingSeconds\": 0\n" +
                "  }\n" +
                "}";

        when(processedEventRepository.existsByEventId("sla-esc-TICK-PROD-100-assigned")).thenReturn(false);
        when(onCallWebhookDispatcher.hasConfiguredChannel()).thenReturn(true);
        when(onCallWebhookDispatcher.dispatchSlack(any(), any())).thenReturn(true);

        consumer.handleTicketEvent(producerEscalationJson);

        ArgumentCaptor<TicketLifecycleEventData> dataCaptor = ArgumentCaptor.forClass(TicketLifecycleEventData.class);
        verify(onCallWebhookDispatcher).dispatchSlack(dataCaptor.capture(), eq("ticket.escalated"));

        TicketLifecycleEventData captured = dataCaptor.getValue();
        assertThat(captured.getTicketNumber()).isEqualTo("TICK-PROD-100");
        assertThat(captured.getTitle()).isEqualTo("Database deadlock on payment gateway");
        assertThat(captured.getSubject()).isEqualTo("Database deadlock on payment gateway");
        assertThat(captured.isEscalated()).isTrue();
        assertThat(captured.getCountdownRemainingSeconds()).isEqualTo(0L);
    }

    @Test
    @DisplayName("ticket.triaged event logs triage info and records ledger")
    void processEvent_ticketTriaged() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-109")
                .assignedAgentId("agent-007")
                .status("TRIAGED")
                .priority("P2")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-109")
                .type("ticket.triaged")
                .data(data)
                .time(Instant.now())
                .build();

        when(processedEventRepository.existsByEventId("evt-109")).thenReturn(false);

        consumer.processEvent(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(onCallWebhookDispatcher);
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("Unhandled event type logs info and records ledger")
    void processEvent_unhandledEventType() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-110")
                .status("UNKNOWN")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-110")
                .type("ticket.custom.event")
                .data(data)
                .time(Instant.now())
                .build();

        when(processedEventRepository.existsByEventId("evt-110")).thenReturn(false);

        consumer.processEvent(event);

        verifyNoInteractions(emailService);
        verifyNoInteractions(onCallWebhookDispatcher);
        verify(processedEventRepository).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("ticket.created and ticket.resolved with null or blank email skips email sending")
    void processEvent_emptyEmailSkipsEmail() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-111")
                .studentEmail("   ")
                .build();

        CloudEvent<TicketLifecycleEventData> createdEvent = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-111-created")
                .type("ticket.created")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-111-created")).thenReturn(false);
        consumer.processEvent(createdEvent);

        CloudEvent<TicketLifecycleEventData> resolvedEvent = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-111-resolved")
                .type("ticket.resolved")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-111-resolved")).thenReturn(false);
        consumer.processEvent(resolvedEvent);

        verifyNoInteractions(emailService);
        verify(processedEventRepository, times(2)).save(any(ProcessedTicketEvent.class));
    }

    @Test
    @DisplayName("recordProcessed catches DB exception without crashing message handling")
    void processEvent_recordProcessedCatchesDbException() {
        TicketLifecycleEventData data = TicketLifecycleEventData.builder()
                .ticketNumber("TICK-112")
                .studentEmail("student@example.com")
                .build();

        CloudEvent<TicketLifecycleEventData> event = CloudEvent.<TicketLifecycleEventData>builder()
                .id("evt-112")
                .type("ticket.created")
                .data(data)
                .build();

        when(processedEventRepository.existsByEventId("evt-112")).thenReturn(false);
        doThrow(new RuntimeException("DB unique index constraint failed"))
                .when(processedEventRepository).save(any(ProcessedTicketEvent.class));

        consumer.processEvent(event);

        verify(emailService).sendEmail(eq("student@example.com"), anyString(), anyString());
    }
}
