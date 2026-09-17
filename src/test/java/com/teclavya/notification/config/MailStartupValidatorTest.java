package com.teclavya.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailStartupValidatorTest {

    @Mock
    private Environment environment;

    @Mock
    private JavaMailSender javaMailSender;

    @Test
    @DisplayName("validate: staging profile with blank host throws IllegalStateException (fails loud)")
    void validate_stagingWithoutHost_throwsException() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        MailStartupValidator validator = new MailStartupValidator(Optional.empty(), environment, "");

        assertThatThrownBy(validator::validateMailConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host is required in staging/prod profiles");
    }

    @Test
    @DisplayName("validate: prod profile with missing sender throws IllegalStateException")
    void validate_prodWithoutSender_throwsException() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        MailStartupValidator validator = new MailStartupValidator(Optional.empty(), environment, "smtp.sendgrid.net");

        assertThatThrownBy(validator::validateMailConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host is required in staging/prod profiles");
    }

    @Test
    @DisplayName("validate: staging profile with configured host and sender succeeds")
    void validate_stagingWithConfiguredMail_succeeds() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"staging"});

        MailStartupValidator validator = new MailStartupValidator(
                Optional.of(javaMailSender), environment, "email-smtp.ap-south-1.amazonaws.com");

        assertThatCode(validator::validateMailConfiguration).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validate: dev profile without host does NOT throw (log-only fallback allowed)")
    void validate_devWithoutHost_doesNotThrow() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        MailStartupValidator validator = new MailStartupValidator(Optional.empty(), environment, "");

        assertThatCode(validator::validateMailConfiguration).doesNotThrowAnyException();
    }
}
