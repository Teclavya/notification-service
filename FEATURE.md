# FEATURE: 1309 - Notification Email Delivery Hardening ([CV-GAP][G23])

## Metadata
- **Feature Name**: `cv-gap-g23-notification-email-harden`
- **Issue**: [Teclavya/teclavya#1309](https://github.com/Teclavya/teclavya/issues/1309)
- **Parent Epic**: [Teclavya/teclavya#375](https://github.com/Teclavya/teclavya/issues/375) (Enterprise B2B College Vertical)
- **LLD Reference**: `specs/_archived/enterprise-college-vertical/lld/C5-notification-email.md`
- **Component**: `notification-service`
- **Target Branch**: `development`
- **Feature Branch**: `feature/cv-gap-g23-notification-email-harden`
- **Status**: Phase 3 (Verification & Ship)

---

## Strategic Intent & Context
In staging and production environments, running with log-only email silently drops critical pilot announcements and student invitations while inflating delivery metrics.
This feature hardens email delivery across four pillars:
1. **Fail-Loud Startup (`MailStartupValidator`)**: Aborts application context initialization on `staging` and `prod` profiles if `spring.mail.host` is missing or unconfigured.
2. **Recipient Suppression (`EmailSuppression` & `SuppressionService`)**: Checks recipient addresses against suppression table (`HARD_BOUNCE`, `UNSUBSCRIBE`, `COMPLAINT`). Suppressed addresses return HTTP 200 with status `SUPPRESSED` and bypass SMTP dispatch, protecting domain sender reputation.
3. **Audit Ledger & Idempotency (`EmailSendEvent` & `EmailSendAuditService`)**: Persists each delivery attempt with timestamp, status, and provider message ID. Deduplicates requests carrying an `idempotencyKey`, returning HTTP 202 `{ "status": "SENT", "idempotencyHit": true }` without resending.
4. **Bounce Webhook Handler (`BounceWebhookController`)**: Ingests automated bounce notifications via `POST /api/v1/notifications/webhook/bounce`, validated via `X-Webhook-Secret`, and auto-registers hard bounces into the suppression list.
5. **Database Migration (`V4__email_suppression.sql`)**: Creates `email_suppression` and `email_send_event` tables with appropriate indexes for performant lookups.

---

## Verification Summary
- **Unit & Slice Tests**: 35 tests covering `MailStartupValidator`, `SuppressionService`, `EmailSendAuditService`, `BounceWebhookController`, and `NotificationControllerEmailHardeningTest`.
- **Full Regression Suite**: 188 tests run, 0 failures, 0 errors, 0 skipped.
- **Reviewer Sign-offs**:
  - Reviewer 1 (Confirmatory): PASS
  - Reviewer 2 (Adversarial): PASS
  - DoD Audit: PASS
