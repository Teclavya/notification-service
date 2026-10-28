package com.teclavya.notification.service;

import com.teclavya.notification.config.OnCallProperties;
import com.teclavya.notification.dto.event.TicketLifecycleEventData;
import com.teclavya.notification.entities.OnCallDispatchAudit;
import com.teclavya.notification.repo.OnCallDispatchAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnCallWebhookDispatcher {

    private final OnCallProperties onCallProperties;
    private final RestTemplate restTemplate;
    private final EmailService emailService;
    private final OnCallDispatchAuditRepository auditRepository;

    public boolean hasConfiguredChannel() {
        if (!onCallProperties.isEnabled()) {
            return false;
        }
        boolean slackConfigured = onCallProperties.getSlackWebhookUrl() != null && !onCallProperties.getSlackWebhookUrl().isBlank();
        boolean discordConfigured = onCallProperties.getDiscordWebhookUrl() != null && !onCallProperties.getDiscordWebhookUrl().isBlank();
        boolean emailConfigured = onCallProperties.isEmailAlertsEnabled()
                && onCallProperties.getAdminAlertEmail() != null
                && !onCallProperties.getAdminAlertEmail().isBlank();
        return slackConfigured || discordConfigured || emailConfigured;
    }

    public boolean dispatchSlack(TicketLifecycleEventData data, String eventType) {
        if (!onCallProperties.isEnabled() || onCallProperties.getSlackWebhookUrl() == null || onCallProperties.getSlackWebhookUrl().isBlank()) {
            log.debug("Slack on-call dispatch skipped (disabled or no webhook URL configured)");
            recordAudit(data.getTicketNumber(), eventType, "SLACK", onCallProperties.getSlackWebhookUrl(), "SKIPPED", "No webhook URL configured or channel disabled");
            return false;
        }

        try {
            Map<String, Object> payload = buildSlackPayload(data);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(onCallProperties.getSlackWebhookUrl(), request, String.class);
            recordAudit(data.getTicketNumber(), eventType, "SLACK", maskUrl(onCallProperties.getSlackWebhookUrl()), "SUCCESS", null);
            log.info("Successfully dispatched Slack on-call escalation alert for ticket #{}", data.getTicketNumber());
            return true;
        } catch (RestClientException e) {
            log.error("Failed to dispatch Slack on-call escalation alert for ticket #{}: {}", data.getTicketNumber(), e.getMessage());
            recordAudit(data.getTicketNumber(), eventType, "SLACK", maskUrl(onCallProperties.getSlackWebhookUrl()), "FAILED", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error dispatching Slack alert for ticket #{}: {}", data.getTicketNumber(), e.getMessage());
            recordAudit(data.getTicketNumber(), eventType, "SLACK", maskUrl(onCallProperties.getSlackWebhookUrl()), "FAILED", e.getMessage());
            return false;
        }
    }

    public boolean dispatchDiscord(TicketLifecycleEventData data, String eventType) {
        if (!onCallProperties.isEnabled() || onCallProperties.getDiscordWebhookUrl() == null || onCallProperties.getDiscordWebhookUrl().isBlank()) {
            log.debug("Discord on-call dispatch skipped (disabled or no webhook URL configured)");
            recordAudit(data.getTicketNumber(), eventType, "DISCORD", onCallProperties.getDiscordWebhookUrl(), "SKIPPED", "No webhook URL configured or channel disabled");
            return false;
        }

        try {
            Map<String, Object> payload = buildDiscordPayload(data);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(onCallProperties.getDiscordWebhookUrl(), request, String.class);
            recordAudit(data.getTicketNumber(), eventType, "DISCORD", maskUrl(onCallProperties.getDiscordWebhookUrl()), "SUCCESS", null);
            log.info("Successfully dispatched Discord on-call escalation alert for ticket #{}", data.getTicketNumber());
            return true;
        } catch (RestClientException e) {
            log.error("Failed to dispatch Discord on-call escalation alert for ticket #{}: {}", data.getTicketNumber(), e.getMessage());
            recordAudit(data.getTicketNumber(), eventType, "DISCORD", maskUrl(onCallProperties.getDiscordWebhookUrl()), "FAILED", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error dispatching Discord alert for ticket #{}: {}", data.getTicketNumber(), e.getMessage());
            recordAudit(data.getTicketNumber(), eventType, "DISCORD", maskUrl(onCallProperties.getDiscordWebhookUrl()), "FAILED", e.getMessage());
            return false;
        }
    }

    public boolean dispatchAdminEmail(TicketLifecycleEventData data, String eventType) {
        if (!onCallProperties.isEnabled() || !onCallProperties.isEmailAlertsEnabled() || onCallProperties.getAdminAlertEmail() == null || onCallProperties.getAdminAlertEmail().isBlank()) {
            log.debug("Admin email on-call dispatch skipped (disabled or no recipient configured)");
            recordAudit(data.getTicketNumber(), eventType, "EMAIL", onCallProperties.getAdminAlertEmail(), "SKIPPED", "Admin email alert disabled or address blank");
            return false;
        }

        try {
            String subject = String.format("[ON-CALL SLA ALERT] Ticket #%s (%s) Escalation", data.getTicketNumber(), data.getPriority());
            String body = buildAdminEmailHtml(data);
            emailService.sendEmail(onCallProperties.getAdminAlertEmail(), subject, body);
            recordAudit(data.getTicketNumber(), eventType, "EMAIL", onCallProperties.getAdminAlertEmail(), "SUCCESS", null);
            log.info("Successfully dispatched on-call alert email for ticket #{} to {}", data.getTicketNumber(), onCallProperties.getAdminAlertEmail());
            return true;
        } catch (Exception e) {
            log.error("Failed to send on-call alert email for ticket #{}: {}", data.getTicketNumber(), e.getMessage());
            recordAudit(data.getTicketNumber(), eventType, "EMAIL", onCallProperties.getAdminAlertEmail(), "FAILED", e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildSlackPayload(TicketLifecycleEventData data) {
        Map<String, Object> payload = new HashMap<>();
        String priorityEmoji = "P0".equalsIgnoreCase(data.getPriority()) ? "🔥" : "⚠️";
        String headerText = String.format("%s *[SLA ESCALATION - %s]* Ticket #%s: %s",
                priorityEmoji, data.getPriority(), data.getTicketNumber(), data.getTitle());

        payload.put("text", headerText);

        List<Map<String, Object>> attachments = new ArrayList<>();
        Map<String, Object> attachment = new HashMap<>();
        attachment.put("color", "P0".equalsIgnoreCase(data.getPriority()) ? "#DC2626" : "#EA580C");

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("title", "Ticket", "value", data.getTicketNumber(), "short", true));
        fields.add(Map.of("title", "Priority", "value", data.getPriority(), "short", true));
        fields.add(Map.of("title", "Category", "value", data.getCategory() != null ? data.getCategory() : "GENERAL", "short", true));
        fields.add(Map.of("title", "Status", "value", data.getStatus() != null ? data.getStatus() : "NEW", "short", true));
        fields.add(Map.of("title", "Assigned Agent", "value", data.getAssignedAgentId() != null ? data.getAssignedAgentId() : "UNASSIGNED", "short", true));
        fields.add(Map.of("title", "Remaining SLA", "value", data.getCountdownRemainingSeconds() != null ? (data.getCountdownRemainingSeconds() + "s") : "BREACHED", "short", true));
        fields.add(Map.of("title", "Reason", "value", data.getEscalationReason() != null ? data.getEscalationReason() : "Automatic SLA threshold escalation", "short", false));

        attachment.put("fields", fields);
        attachments.add(attachment);
        payload.put("attachments", attachments);

        return payload;
    }

    private Map<String, Object> buildDiscordPayload(TicketLifecycleEventData data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", "Teclavya SRE On-Call Engine");

        Map<String, Object> embed = new HashMap<>();
        embed.put("title", String.format("🚨 [SLA Escalation Alert] Ticket #%s (%s)", data.getTicketNumber(), data.getPriority()));
        embed.put("description", String.format("**Title:** %s\n**Reason:** %s",
                data.getTitle(),
                data.getEscalationReason() != null ? data.getEscalationReason() : "Automated SLA threshold reached"));

        int color = "P0".equalsIgnoreCase(data.getPriority()) ? 0xDC2626 : 0xEA580C;
        embed.put("color", color);
        embed.put("timestamp", Instant.now().toString());

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(Map.of("name", "Category", "value", data.getCategory() != null ? data.getCategory() : "GENERAL", "inline", true));
        fields.add(Map.of("name", "Status", "value", data.getStatus() != null ? data.getStatus() : "NEW", "inline", true));
        fields.add(Map.of("name", "Agent", "value", data.getAssignedAgentId() != null ? data.getAssignedAgentId() : "UNASSIGNED", "inline", true));
        fields.add(Map.of("name", "Remaining SLA", "value", data.getCountdownRemainingSeconds() != null ? (data.getCountdownRemainingSeconds() + " seconds") : "BREACHED", "inline", true));

        embed.put("fields", fields);
        payload.put("embeds", List.of(embed));

        return payload;
    }

    private String buildAdminEmailHtml(TicketLifecycleEventData data) {
        return "<!DOCTYPE html><html><head><style>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1e293b; background-color: #f8fafc; padding: 20px; }"
                + ".card { background: #ffffff; border-radius: 8px; border: 1px solid #e2e8f0; max-width: 600px; margin: 0 auto; padding: 24px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); }"
                + ".badge { display: inline-block; padding: 4px 8px; font-size: 12px; font-weight: 700; border-radius: 4px; color: #fff; background-color: #dc2626; }"
                + ".details { width: 100%; border-collapse: collapse; margin-top: 16px; }"
                + ".details td { padding: 8px 12px; border-bottom: 1px solid #f1f5f9; font-size: 14px; }"
                + ".details td.label { font-weight: 600; color: #64748b; width: 35%; }"
                + "</style></head><body>"
                + "<div class=\"card\">"
                + "<h2>🚨 Urgent Support Ticket Escalation</h2>"
                + "<p>An automated SLA escalation has triggered for ticket <strong>#" + data.getTicketNumber() + "</strong>.</p>"
                + "<table class=\"details\">"
                + "<tr><td class=\"label\">Ticket Number</td><td>" + data.getTicketNumber() + "</td></tr>"
                + "<tr><td class=\"label\">Priority</td><td><span class=\"badge\">" + data.getPriority() + "</span></td></tr>"
                + "<tr><td class=\"label\">Title</td><td>" + data.getTitle() + "</td></tr>"
                + "<tr><td class=\"label\">Category</td><td>" + (data.getCategory() != null ? data.getCategory() : "N/A") + "</td></tr>"
                + "<tr><td class=\"label\">Status</td><td>" + data.getStatus() + "</td></tr>"
                + "<tr><td class=\"label\">Assigned Agent</td><td>" + (data.getAssignedAgentId() != null ? data.getAssignedAgentId() : "<em>Unassigned</em>") + "</td></tr>"
                + "<tr><td class=\"label\">Escalation Reason</td><td>" + (data.getEscalationReason() != null ? data.getEscalationReason() : "SLA 75% unassigned threshold reached") + "</td></tr>"
                + "<tr><td class=\"label\">Remaining SLA</td><td>" + (data.getCountdownRemainingSeconds() != null ? data.getCountdownRemainingSeconds() + " seconds" : "BREACHED") + "</td></tr>"
                + "</table>"
                + "<p style=\"margin-top: 20px; font-size: 13px; color: #94a3b8;\">Teclavya Automated SLA Monitoring &amp; On-Call Escalation Engine</p>"
                + "</div></body></html>";
    }

    private void recordAudit(String ticketNumber, String eventType, String channel, String urlOrRecipient, String status, String errorMessage) {
        try {
            OnCallDispatchAudit audit = OnCallDispatchAudit.builder()
                    .ticketNumber(ticketNumber != null ? ticketNumber : "UNKNOWN")
                    .eventType(eventType != null ? eventType : "ticket.escalated")
                    .channel(channel)
                    .recipientOrUrl(urlOrRecipient)
                    .status(status)
                    .errorMessage(errorMessage)
                    .dispatchedAt(Instant.now())
                    .build();
            auditRepository.save(audit);
        } catch (Exception e) {
            log.warn("Failed to persist on-call dispatch audit record: {}", e.getMessage());
        }
    }

    private String maskUrl(String url) {
        if (url == null || url.length() <= 15) return "***";
        return url.substring(0, 10) + "..." + url.substring(url.length() - 5);
    }
}
