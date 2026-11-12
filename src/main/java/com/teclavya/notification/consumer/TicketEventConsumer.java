package com.teclavya.notification.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teclavya.notification.config.RabbitMQConfig;
import com.teclavya.notification.dto.event.CloudEvent;
import com.teclavya.notification.dto.event.TicketLifecycleEventData;
import com.teclavya.notification.entities.ProcessedTicketEvent;
import com.teclavya.notification.repo.ProcessedTicketEventRepository;
import com.teclavya.notification.service.EmailService;
import com.teclavya.notification.service.OnCallWebhookDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
public class TicketEventConsumer {

    private final ProcessedTicketEventRepository processedEventRepository;
    private final OnCallWebhookDispatcher onCallWebhookDispatcher;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    public TicketEventConsumer(
            ProcessedTicketEventRepository processedEventRepository,
            OnCallWebhookDispatcher onCallWebhookDispatcher,
            EmailService emailService,
            ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.onCallWebhookDispatcher = onCallWebhookDispatcher;
        this.emailService = emailService;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void handleTicketEvent(String rawPayload) {
        CloudEvent<TicketLifecycleEventData> event;
        try {
            event = objectMapper.readValue(rawPayload, new TypeReference<CloudEvent<TicketLifecycleEventData>>() {});
        } catch (Exception e) {
            log.error("Failed to parse ticket event payload, rejecting to DLQ: {}", e.getMessage(), e);
            throw new AmqpRejectAndDontRequeueException("Invalid ticket event payload", e);
        }

        processEvent(event);
    }

    public void processEvent(CloudEvent<TicketLifecycleEventData> event) {
        if (event == null || event.getId() == null || event.getType() == null) {
            log.warn("Received malformed or null CloudEvent, rejecting to DLQ");
            throw new AmqpRejectAndDontRequeueException("Malformed CloudEvent: missing id or type");
        }

        if (processedEventRepository.existsByEventId(event.getId())) {
            log.info("Ticket event '{}' (id={}) already processed, skipping duplicate",
                    event.getType(), event.getId());
            return;
        }

        TicketLifecycleEventData data = event.getData();
        if (data == null) {
            log.warn("CloudEvent {} contains no data payload, rejecting to DLQ", event.getId());
            throw new AmqpRejectAndDontRequeueException("CloudEvent missing data payload");
        }

        log.info("Processing CloudEvent '{}' for ticket #{} (priority={})",
                event.getType(), data.getTicketNumber(), data.getPriority());

        switch (event.getType()) {
            case "ticket.created":
                handleTicketCreated(data);
                break;
            case "ticket.triaged":
                handleTicketTriaged(data);
                break;
            case "ticket.escalated":
                handleTicketEscalated(data, event.getType());
                break;
            case "ticket.resolved":
                handleTicketResolved(data);
                break;
            default:
                log.info("Unhandled ticket event type '{}' for ticket #{}", event.getType(), data.getTicketNumber());
                break;
        }

        // Record processed event in deduplication ledger only after successful execution
        recordProcessed(event.getId(), event.getType(), data.getTicketNumber());
    }

    private void handleTicketCreated(TicketLifecycleEventData data) {
        if (data.getStudentEmail() != null && !data.getStudentEmail().isBlank()) {
            String subject = String.format("Support Request Received [#%s]: %s", data.getTicketNumber(), data.getTitle());
            String htmlBody = buildTicketCreatedEmailHtml(data);
            emailService.sendEmail(data.getStudentEmail(), subject, htmlBody);
            log.info("Sent ticket created confirmation email to {}", data.getStudentEmail());
        }
    }

    private void handleTicketTriaged(TicketLifecycleEventData data) {
        log.info("Ticket #{} triaged: assignedTo={}, status={}, priority={}",
                data.getTicketNumber(), data.getAssignedAgentId(), data.getStatus(), data.getPriority());
    }

    private void handleTicketEscalated(TicketLifecycleEventData data, String eventType) {
        log.warn("Escalating ticket #{} across on-call channels (reason={})",
                data.getTicketNumber(), data.getEscalationReason());
        boolean slackDispatched = onCallWebhookDispatcher.dispatchSlack(data, eventType);
        boolean discordDispatched = onCallWebhookDispatcher.dispatchDiscord(data, eventType);
        boolean emailDispatched = onCallWebhookDispatcher.dispatchAdminEmail(data, eventType);

        if (onCallWebhookDispatcher.hasConfiguredChannel() && !slackDispatched && !discordDispatched && !emailDispatched) {
            throw new IllegalStateException(String.format(
                    "All configured on-call channels failed for ticket #%s escalation. Triggering retry.",
                    data.getTicketNumber()));
        }
    }

    private void handleTicketResolved(TicketLifecycleEventData data) {
        if (data.getStudentEmail() != null && !data.getStudentEmail().isBlank()) {
            String subject = String.format("Support Ticket Resolved [#%s]: %s", data.getTicketNumber(), data.getTitle());
            String htmlBody = buildTicketResolvedEmailHtml(data);
            emailService.sendEmail(data.getStudentEmail(), subject, htmlBody);
            log.info("Sent ticket resolved confirmation email to {}", data.getStudentEmail());
        }
    }

    private void recordProcessed(String eventId, String eventType, String ticketNumber) {
        try {
            ProcessedTicketEvent record = ProcessedTicketEvent.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .ticketNumber(ticketNumber != null ? ticketNumber : "UNKNOWN")
                    .processedAt(Instant.now())
                    .build();
            processedEventRepository.save(record);
        } catch (Exception e) {
            log.warn("Failed to persist processed ticket event ledger record: {}", e.getMessage());
        }
    }

    private String buildTicketCreatedEmailHtml(TicketLifecycleEventData data) {
        return "<!DOCTYPE html><html><body>"
                + "<h3>We received your support ticket</h3>"
                + "<p>Hi,</p>"
                + "<p>Your ticket <strong>#" + data.getTicketNumber() + "</strong> (\"" + data.getTitle() + "\") has been registered in our system.</p>"
                + "<p>Priority: <strong>" + data.getPriority() + "</strong></p>"
                + "<p>Our engineering and support team is actively reviewing your request.</p>"
                + "<p>Thanks,<br/>Teclavya Support Team</p>"
                + "</body></html>";
    }

    private String buildTicketResolvedEmailHtml(TicketLifecycleEventData data) {
        return "<!DOCTYPE html><html><body>"
                + "<h3>Your support ticket has been resolved</h3>"
                + "<p>Hi,</p>"
                + "<p>Your ticket <strong>#" + data.getTicketNumber() + "</strong> (\"" + data.getTitle() + "\") has been marked as resolved.</p>"
                + "<p>If your issue is not completely solved, you may reply or re-open the ticket from your student dashboard.</p>"
                + "<p>Thanks,<br/>Teclavya Support Team</p>"
                + "</body></html>";
    }
}
