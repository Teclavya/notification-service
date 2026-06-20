package com.teclavya.notification.lifecycle.verifier;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based implementation of {@link ContentSafetyVerifier}.
 *
 * <h3>MVP rules (applied in order; first failure wins)</h3>
 * <ol>
 *   <li><b>Length</b> — email notifications: &lt;= 500 chars; all others (in-app): &lt;= 300 chars.</li>
 *   <li><b>Placeholder schema</b> — every <code>{token}</code> in the body must be in the
 *       allowed set: {@code studentName, pathName, topicName, daysSinceActive}.</li>
 *   <li><b>No-PII</b> — reject bodies that contain a raw email address or an SSN-like pattern.</li>
 * </ol>
 *
 * <h3>Fail-closed contract</h3>
 * The rule evaluation is wrapped by a Resilience4j {@link CircuitBreaker} and
 * {@link TimeLimiter} (instance name {@value #INSTANCE_NAME}, 4-second timeout).
 * Any exception or timeout causes this method to return {@code SafetyVerdict.fail("verifier-error: <cause>")}
 * — it NEVER throws, and NEVER returns PASS on error.
 */
@Service
@Slf4j
public class RuleBasedContentSafetyVerifier implements ContentSafetyVerifier {

    static final String INSTANCE_NAME = "contentSafetyVerifier";

    // -----------------------------------------------------------------------
    // Rule constants
    // -----------------------------------------------------------------------

    private static final int MAX_LENGTH_EMAIL = 500;
    private static final int MAX_LENGTH_IN_APP = 300;

    /**
     * Notification type prefixes / keywords that identify an email channel.
     * Matching is case-insensitive substring check.
     */
    private static final String EMAIL_KEYWORD = "email";

    /** Allowed placeholder tokens inside curly braces. */
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "studentName", "pathName", "topicName", "daysSinceActive"
    );

    /** Pattern to extract all {token} occurrences from a body. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)}");

    /** Simplified RFC-5321 email regex — catches raw email addresses in body text. */
    private static final Pattern RAW_EMAIL_PATTERN =
            Pattern.compile("[\\w.+\\-]+@[\\w\\-]+\\.[\\w.\\-]+");

    /** SSN-like pattern: NNN-NN-NNNN. */
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

    // -----------------------------------------------------------------------
    // Resilience4j wiring
    // -----------------------------------------------------------------------

    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    public RuleBasedContentSafetyVerifier(CircuitBreakerRegistry circuitBreakerRegistry,
                                          TimeLimiterRegistry timeLimiterRegistry) {
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.timeLimiter   = timeLimiterRegistry.timeLimiter(INSTANCE_NAME);
        // Single virtual/platform thread for the timed future; lightweight.
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "safety-verifier");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // ContentSafetyVerifier
    // -----------------------------------------------------------------------

    @Override
    public SafetyVerdict verify(String messageBody, String notificationType) {
        try {
            Callable<SafetyVerdict> timedCall = TimeLimiter.decorateFutureSupplier(
                    timeLimiter,
                    () -> CompletableFuture.supplyAsync(
                            () -> runRules(messageBody, notificationType),
                            executor));

            Callable<SafetyVerdict> resilientCall =
                    CircuitBreaker.decorateCallable(circuitBreaker, timedCall);

            return resilientCall.call();

        } catch (Exception ex) {
            // FAIL-CLOSED: any exception (timeout, circuit open, NPE inside rule, etc.) → FAIL
            String cause = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("ContentSafetyVerifier error — returning FAIL (fail-closed). cause={}", cause);
            return SafetyVerdict.fail("verifier-error: " + cause);
        }
    }

    // -----------------------------------------------------------------------
    // Rule evaluation (package-private for direct unit testing without Resilience4j)
    // -----------------------------------------------------------------------

    SafetyVerdict runRules(String messageBody, String notificationType) {
        if (messageBody == null) {
            return SafetyVerdict.fail("message body is null");
        }

        // Rule 1 — Length
        boolean isEmail = notificationType != null
                && notificationType.toLowerCase().contains(EMAIL_KEYWORD);
        int maxLength = isEmail ? MAX_LENGTH_EMAIL : MAX_LENGTH_IN_APP;
        if (messageBody.length() > maxLength) {
            return SafetyVerdict.fail(
                    "body length " + messageBody.length() + " exceeds max " + maxLength
                    + " for channel " + (isEmail ? "email" : "in-app"));
        }

        // Rule 2 — Placeholder schema
        Matcher m = PLACEHOLDER_PATTERN.matcher(messageBody);
        while (m.find()) {
            String token = m.group(1);
            if (!ALLOWED_PLACEHOLDERS.contains(token)) {
                return SafetyVerdict.fail("unknown placeholder: {" + token + "}");
            }
        }

        // Rule 3 — No-PII
        if (RAW_EMAIL_PATTERN.matcher(messageBody).find()) {
            return SafetyVerdict.fail("body contains a raw email address (PII)");
        }
        if (SSN_PATTERN.matcher(messageBody).find()) {
            return SafetyVerdict.fail("body contains an SSN-like pattern (PII)");
        }

        return SafetyVerdict.pass("all rules passed");
    }
}
