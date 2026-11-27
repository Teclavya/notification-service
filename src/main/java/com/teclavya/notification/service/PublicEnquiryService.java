package com.teclavya.notification.service;

import com.teclavya.notification.dto.request.PublicEnquiryRequest;
import com.teclavya.notification.dto.response.PublicEnquiryResponse;
import com.teclavya.notification.entities.PublicEnquiry;
import com.teclavya.notification.repo.PublicEnquiryRepository;
import com.teclavya.notification.service.impl.EmailTemplateRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PublicEnquiryService {

    private final PublicEnquiryRepository repository;
    private final EmailService emailService;
    private final EmailTemplateRenderer templateRenderer;

    /**
     * Persists a validated public enquiry and dispatches it to the mapped Hostinger alias.
     * Honeypot-populated requests are silently accepted without persisting or sending email.
     */
    public PublicEnquiryResponse submit(PublicEnquiryRequest request, String clientIp) {
        PublicEnquiryRequest.EnquiryTopic topic = request.getTopic();

        if (request.getWebsite() != null && !request.getWebsite().isBlank()) {
            log.info("public_enquiry_honeypot_dropped topic={}", topic.getCode());
            return PublicEnquiryResponse.builder()
                    .referenceId("ENQ-RECEIVED")
                    .routedTo(topic.getTargetAlias())
                    .sla(topic.getSla())
                    .build();
        }

        String referenceId = "ENQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String safeName = stripCrLf(request.getFullName().trim());
        String safeEmail = stripCrLf(request.getEmail().trim());
        String trimmedMessage = request.getMessage().trim();

        PublicEnquiry entity = PublicEnquiry.builder()
                .id(UUID.randomUUID())
                .referenceId(referenceId)
                .topic(topic.getCode())
                .targetAlias(topic.getTargetAlias())
                .senderName(safeName)
                .senderEmail(safeEmail)
                .messageBody(trimmedMessage)
                .clientIpHash(sha256(clientIp))
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
        repository.save(entity);

        Map<String, String> params = Map.of(
                "referenceId", referenceId,
                "topicLabel", HtmlUtils.htmlEscape(topic.getLabel()),
                "targetAlias", HtmlUtils.htmlEscape(topic.getTargetAlias()),
                "senderName", HtmlUtils.htmlEscape(safeName),
                "senderEmail", HtmlUtils.htmlEscape(safeEmail),
                "messageBody", HtmlUtils.htmlEscape(trimmedMessage)
        );
        String htmlBody = templateRenderer.render("contact-enquiry", params);
        String subject = "[" + referenceId + "] " + topic.getLabel() + " — " + safeName;

        try {
            emailService.sendEmail(topic.getTargetAlias(), subject, htmlBody, safeEmail);
            entity.setStatus("SENT");
            repository.save(entity);
            log.info("public_enquiry_sent ref={} topic={} target={}", referenceId, topic.getCode(), topic.getTargetAlias());
        } catch (MailException ex) {
            entity.setStatus("FAILED");
            repository.save(entity);
            log.error("public_enquiry_failed ref={} target={} err={}", referenceId, topic.getTargetAlias(), ex.getMessage());
            throw ex;
        }

        return PublicEnquiryResponse.builder()
                .referenceId(referenceId)
                .routedTo(topic.getTargetAlias())
                .sla(topic.getSla())
                .build();
    }

    private static String stripCrLf(String value) {
        return value.replaceAll("[\\r\\n]+", " ");
    }

    private static String sha256(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
