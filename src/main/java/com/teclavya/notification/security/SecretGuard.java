package com.teclavya.notification.security;

import java.util.Set;

/**
 * Fail-loud guard for HMAC signing secrets (P0-1 secret hygiene). Refuses to start the
 * service with an unset, blank, placeholder, too-short, or known-compromised secret rather
 * than silently falling back to a weak/shared default.
 *
 * <p>Adapted from recruiter-portal-service's {@code com.teclavya.recruiter.security.SecretGuard}
 * (Epic #1336), with an added check for the fleet-wide leaked dev-default hex key that this
 * service's {@code application.yml} previously fell back to.
 */
public final class SecretGuard {

    private static final int MIN_BYTES = 32;
    private static final Set<String> PLACEHOLDERS = Set.of(
            "change-me-in-deployment", "changeme", "secret", "test", "password");

    /** Known-compromised dev-default hex signing key that leaked across the fleet's application.yml files. */
    private static final Set<String> KNOWN_COMPROMISED = Set.of(
            "4D6251655468576D5A7134743777217A25432A462D4A614E645267556B587032");

    private SecretGuard() {
    }

    public static String requireStrong(String propertyName, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(propertyName + " is required and must not be blank. "
                    + "Set it via environment variable before starting the service.");
        }
        String trimmed = secret.trim();
        if (PLACEHOLDERS.contains(trimmed.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException(propertyName + " must not be a placeholder value ('" + trimmed + "').");
        }
        if (KNOWN_COMPROMISED.contains(trimmed)) {
            throw new IllegalStateException(propertyName + " uses a known-compromised secret value that has "
                    + "leaked across the fleet's default configs. Generate and set a fresh secret via "
                    + "environment variable.");
        }
        if (trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_BYTES) {
            throw new IllegalStateException(propertyName + " must be at least " + MIN_BYTES
                    + " bytes; got " + trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ".");
        }
        return trimmed;
    }
}
