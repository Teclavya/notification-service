package com.teclavya.notification.lifecycle.verifier;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RuleBasedContentSafetyVerifier}.
 *
 * <p>Tests exercise {@code runRules()} directly to avoid threading overhead, and also
 * exercise the full {@code verify()} path (with live Resilience4j default registries)
 * to prove the fail-closed wrapper works.
 */
class RuleBasedContentSafetyVerifierTest {

    private RuleBasedContentSafetyVerifier verifier;

    @BeforeEach
    void setUp() {
        // Use default (no-op/permissive) registries — no application context needed.
        verifier = new RuleBasedContentSafetyVerifier(
                CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults());
    }

    // -----------------------------------------------------------------------
    // Fail-closed: internal exception → FAIL (BE-3 / QA-1 primary requirement)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Fail-closed guarantee")
    class FailClosedTests {

        @Test
        @DisplayName("An internal exception from a subclass → verify() returns FAIL, never throws")
        void verify_internalException_returnsFail() {
            // Subclass that deliberately throws inside runRules
            RuleBasedContentSafetyVerifier broken = new RuleBasedContentSafetyVerifier(
                    CircuitBreakerRegistry.ofDefaults(),
                    TimeLimiterRegistry.ofDefaults()) {
                @Override
                SafetyVerdict runRules(String body, String type) {
                    throw new RuntimeException("simulated internal failure");
                }
            };

            SafetyVerdict result = broken.verify("any body", "INACTIVITY_REENGAGEMENT");

            assertThat(result.pass()).isFalse();
            assertThat(result.details()).contains("verifier-error");
            assertThat(result.verdictString()).isEqualTo("FAIL");
        }
    }

    // -----------------------------------------------------------------------
    // Rule 1 — Length
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Rule 1: Length")
    class LengthRuleTests {

        @Test
        @DisplayName("In-app: body at exactly 300 chars → PASS")
        void inApp_exactLimit_pass() {
            String body = "A".repeat(300);
            assertThat(verifier.runRules(body, "INACTIVITY_REENGAGEMENT").pass()).isTrue();
        }

        @Test
        @DisplayName("In-app: body at 301 chars → FAIL")
        void inApp_overLimit_fail() {
            String body = "A".repeat(301);
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).contains("301").contains("300");
        }

        @Test
        @DisplayName("Email type: body at 500 chars → PASS")
        void email_exactLimit_pass() {
            String body = "A".repeat(500);
            assertThat(verifier.runRules(body, "email_COHORT_INVITE").pass()).isTrue();
        }

        @Test
        @DisplayName("Email type: body at 501 chars → FAIL")
        void email_overLimit_fail() {
            String body = "A".repeat(501);
            SafetyVerdict verdict = verifier.runRules(body, "EMAIL_COHORT_INVITE");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).contains("501").contains("500");
        }

        @Test
        @DisplayName("Null body → FAIL (never throws)")
        void nullBody_fail() {
            SafetyVerdict verdict = verifier.runRules(null, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Rule 2 — Placeholder schema
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Rule 2: Placeholder schema")
    class PlaceholderRuleTests {

        @Test
        @DisplayName("Known placeholders only → PASS")
        void knownPlaceholders_pass() {
            String body = "Hi {studentName}, your {pathName} awaits. Topic: {topicName}. Days: {daysSinceActive}.";
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isTrue();
        }

        @Test
        @DisplayName("Unknown placeholder → FAIL")
        void unknownPlaceholder_fail() {
            String body = "Hi {studentName}, see {unknownToken} here.";
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).contains("unknownToken");
        }

        @Test
        @DisplayName("Body with no placeholders → PASS")
        void noPlaceholders_pass() {
            String body = "Welcome back! Log in today.";
            assertThat(verifier.runRules(body, "ONBOARDING_NUDGE").pass()).isTrue();
        }

        @Test
        @DisplayName("Body with only unknown placeholder → FAIL")
        void onlyUnknownPlaceholder_fail() {
            String body = "{firstName} has new content.";
            SafetyVerdict verdict = verifier.runRules(body, "MASTERY_CELEBRATION");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).contains("firstName");
        }
    }

    // -----------------------------------------------------------------------
    // Rule 3 — No-PII
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Rule 3: No-PII")
    class PiiRuleTests {

        @Test
        @DisplayName("Raw email address in body → FAIL")
        void rawEmail_fail() {
            String body = "Contact us at support@example.com for help.";
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).containsIgnoringCase("email");
        }

        @Test
        @DisplayName("SSN-like pattern in body → FAIL")
        void ssn_fail() {
            String body = "Your reference: 123-45-6789. Please act now.";
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isFalse();
            assertThat(verdict.details()).containsIgnoringCase("ssn");
        }

        @Test
        @DisplayName("Clean template with allowed placeholders → PASS")
        void cleanTemplate_pass() {
            String body = "Hi {studentName}, your {pathName} awaits!";
            SafetyVerdict verdict = verifier.runRules(body, "INACTIVITY_REENGAGEMENT");
            assertThat(verdict.pass()).isTrue();
            assertThat(verdict.verdictString()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("Phone number (not matching SSN pattern) → PASS (not in scope)")
        void phoneNumber_pass() {
            String body = "Call 555-1234 to reach your mentor.";
            // 555-1234 is 3-4 digits, not SSN format NNN-NN-NNNN
            assertThat(verifier.runRules(body, "INACTIVITY_REENGAGEMENT").pass()).isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // SafetyVerdict helper
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SafetyVerdict.verdictString() maps pass→PASS, fail→FAIL")
    void safetyVerdict_verdictString() {
        assertThat(SafetyVerdict.pass("ok").verdictString()).isEqualTo("PASS");
        assertThat(SafetyVerdict.fail("bad").verdictString()).isEqualTo("FAIL");
    }

    // -----------------------------------------------------------------------
    // Full verify() path — smoke tests through the Resilience4j wrapper
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Full verify() path")
    class VerifyPathTests {

        @Test
        @DisplayName("verify() returns PASS for a clean body")
        void verify_cleanBody_pass() {
            SafetyVerdict result = verifier.verify(
                    "Hi {studentName}, your {pathName} awaits!", "INACTIVITY_REENGAGEMENT");
            assertThat(result.pass()).isTrue();
            assertThat(result.verdictString()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("verify() returns FAIL for over-length body")
        void verify_overLength_fail() {
            SafetyVerdict result = verifier.verify("X".repeat(400), "INACTIVITY_REENGAGEMENT");
            assertThat(result.pass()).isFalse();
        }

        @Test
        @DisplayName("verify() returns FAIL for unknown placeholder")
        void verify_unknownPlaceholder_fail() {
            SafetyVerdict result = verifier.verify(
                    "Hi {badToken}!", "STRUGGLE_INTERVENTION");
            assertThat(result.pass()).isFalse();
        }

        @Test
        @DisplayName("verify() returns FAIL for raw email PII")
        void verify_rawEmailPii_fail() {
            SafetyVerdict result = verifier.verify(
                    "Reply to admin@teclavya.com", "INACTIVITY_REENGAGEMENT");
            assertThat(result.pass()).isFalse();
        }

        @Test
        @DisplayName("verify() returns FAIL for SSN PII")
        void verify_ssnPii_fail() {
            SafetyVerdict result = verifier.verify(
                    "Ref 987-65-4321", "INACTIVITY_REENGAGEMENT");
            assertThat(result.pass()).isFalse();
        }
    }
}
