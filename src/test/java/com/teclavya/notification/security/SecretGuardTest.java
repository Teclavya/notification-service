package com.teclavya.notification.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretGuardTest {

    @Test void rejectsNullOrBlank() {
        assertThatThrownBy(() -> SecretGuard.requireStrong("prop", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretGuard.requireStrong("prop", "   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsPlaceholderValues() {
        assertThatThrownBy(() -> SecretGuard.requireStrong("prop", "change-me-in-deployment")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretGuard.requireStrong("prop", "CHANGEME")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsTooShortSecrets() {
        assertThatThrownBy(() -> SecretGuard.requireStrong("prop", "short-secret-16b")).isInstanceOf(IllegalStateException.class);
    }

    @Test void rejectsKnownCompromisedSecret() {
        assertThatThrownBy(() -> SecretGuard.requireStrong("application.jwt.secret",
                "4D6251655468576D5A7134743777217A25432A462D4A614E645267556B587032"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void acceptsStrongSecret() {
        String strong = "this-is-a-sufficiently-long-real-secret-value";
        assertThat(SecretGuard.requireStrong("prop", strong)).isEqualTo(strong);
    }
}
