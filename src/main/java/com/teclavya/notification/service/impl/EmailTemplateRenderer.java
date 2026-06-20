package com.teclavya.notification.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads HTML email templates from {@code classpath:templates/} and substitutes
 * {@code {key}} placeholders with caller-supplied params.
 *
 * <p>No Thymeleaf or Freemarker dependency — design §BE-5 decision #10 keeps the
 * dependency surface minimal.
 */
@Component
@Slf4j
public class EmailTemplateRenderer {

    /**
     * Load and render a template.
     *
     * @param templateId file name without extension (e.g. "cohort-invite")
     * @param params     placeholder substitutions; each {@code {key}} in the template
     *                   is replaced with the corresponding value
     * @return rendered HTML string
     * @throws IllegalArgumentException if the template file cannot be found
     */
    public String render(String templateId, Map<String, String> params) {
        String resourcePath = "templates/" + templateId + ".html";
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Email template not found: " + resourcePath);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    content = content.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
            return content;
        } catch (IOException e) {
            log.error("Failed to read email template '{}': {}", resourcePath, e.getMessage());
            throw new IllegalStateException("Cannot read email template: " + resourcePath, e);
        }
    }
}
