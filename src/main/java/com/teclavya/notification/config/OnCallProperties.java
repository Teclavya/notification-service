package com.teclavya.notification.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "notification.oncall")
public class OnCallProperties {
    private boolean enabled = true;
    private String slackWebhookUrl = "";
    private String discordWebhookUrl = "";
    private String adminAlertEmail = "oncall-alerts@teclavya.com";
    private boolean emailAlertsEnabled = true;
}
