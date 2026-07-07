package com.teclavya.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Wires JavaMailSender from spring.mail.* properties (env-driven).
 * Only registered when spring.mail.host is present; in dev/no-credentials mode
 * the bean is absent and EmailService logs instead of sending.
 */
@Configuration
@ConditionalOnExpression(
        "T(org.springframework.util.StringUtils).hasText('${spring.mail.host:}') and " +
        "T(org.springframework.util.StringUtils).hasText('${spring.mail.username:}') and " +
        "T(org.springframework.util.StringUtils).hasText('${spring.mail.password:}')")
@Slf4j
public class MailConfig {

    @Value("${spring.mail.host}")
    private String host;

    @Value("${spring.mail.port:587}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private String smtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}")
    private String starttlsEnable;

    @Bean
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", smtpAuth);
        props.put("mail.smtp.starttls.enable", starttlsEnable);
        props.put("mail.transport.protocol", "smtp");

        log.info("JavaMailSender configured for host '{}:{}'", host, port);
        return sender;
    }
}
