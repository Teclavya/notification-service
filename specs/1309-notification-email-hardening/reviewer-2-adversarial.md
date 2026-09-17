# Reviewer 2: Adversarial Verification

- **Feature**: `cv-gap-g23-notification-email-harden`
- **Issue**: [Teclavya/teclavya#1309](https://github.com/Teclavya/teclavya/issues/1309)
- **Reviewer**: Adversarial Reviewer (Persona: Security & Edge Case Auditor)
- **Model**: Adversarial Multi-Model Check
- **Date**: 2026-09-17
- **Verdict**: ✅ **PASS**

---

## Adversarial Inquiries & Refutation Checks

### Attack 1: Whitespace or Invalid `spring.mail.host` in Production
- **Hypothesis**: Could an operator configure `spring.mail.host: "   "` and bypass validation, leading to silent drops?
- **Verification**: `MailStartupValidator` uses `StringUtils.hasText(mailHost)`. A whitespace-only string returns `false` and triggers `IllegalStateException: CRITICAL: spring.mail.host must be configured...`.
- **Refutation Test**: Verified in `MailStartupValidatorTest#shouldFailFastOnProdWhenMailHostIsBlank`.

### Attack 2: Case Sensitivity in Recipient Email Suppression
- **Hypothesis**: Could an email like `User@Example.Com` bypass suppression if recorded as `user@example.com`?
- **Verification**: `SuppressionService` normalizes all emails via `email.trim().toLowerCase(Locale.ROOT)` on check, suppression upsert, and unsuppress operations.
- **Refutation Test**: Verified in `SuppressionServiceTest#shouldMatchSuppressionCaseInsensitively`.

### Attack 3: Unauthorized Bounce Ingestion
- **Hypothesis**: Could an unauthenticated caller trigger `POST /api/v1/notifications/webhook/bounce` to suppress legitimate user emails via denial-of-service?
- **Verification**: The endpoint strictly checks `X-Webhook-Secret`. If missing or invalid, it immediately rejects the request with HTTP 401 Unauthorized. If the webhook secret is not configured on the server, it fails closed with HTTP 401.
- **Refutation Test**: Verified in `BounceWebhookControllerTest#shouldRejectMissingOrInvalidSecret`.

### Attack 4: Duplicate Concurrent Idempotency Submissions
- **Hypothesis**: What happens if an idempotency key is submitted twice?
- **Verification**: `EmailSendEventRepository.findByCustomerKey(idempotencyKey)` queries for existing records. If an existing `SENT` event exists, `NotificationController` short-circuits and returns HTTP 202 `{ "status": "SENT", "idempotencyHit": true }` without dispatching to `emailService`. If no prior sent event exists, the event is audited upon successful dispatch.
- **Refutation Test**: Verified in `NotificationControllerEmailHardeningTest#shouldReturnIdempotencyHitWhenDuplicateKeyProvided`.

### Attack 5: Flyway DDL Safety
- **Hypothesis**: Will Flyway migration `V4__email_suppression.sql` block or fail if deployed against a replica or existing schema?
- **Verification**: Uses `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`. No destructive column alterations, no drop operations, and no sequence conflicts.

**Verdict**: PASS. No unresolved vulnerabilities or edge-case oversights identified.
