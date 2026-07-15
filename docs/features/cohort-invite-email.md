# FEATURE: cohort-invite-email (BE-5 + TEST-4)

_Last updated: 2026-06-20_

## Status
DONE — Gate-1 GREEN (26 tests, 0 failures)

## What was done
- Wired `JavaMailSender` from env-driven `spring.mail.*` via `MailConfig` (`@ConditionalOnProperty("spring.mail.host")`).
- Added `EmailService` interface + `EmailServiceImpl`: sends HTML email via `MimeMessage`; when sender absent → log-only fallback (dev mode, no crash).
- Added `EmailTemplateRenderer`: loads `classpath:templates/{id}.html`, substitutes `{key}` placeholders.
- Added `cohort-invite-email.html` template ("You're invited to join {cohortName}").
- Added `POST /api/v1/notifications/email` to `NotificationController`:
  - Auth: `X-Internal-Token` header vs `${INTERNAL_SERVICE_TOKEN}` env var; 401 if mismatch.
  - 202 `{ "status": "SENT" }` on success; 500 on `MailException`.
- Updated `SecurityConfig`: permit `/api/v1/notifications/email` (controller handles auth).
- Updated `application.yml`: `spring.mail.*` + `internal.service.token` stubs (all env-driven).
- TEST-4: `EmailServiceTest` (3 Mockito unit tests) + `NotificationControllerEmailTest` (5 @WebMvcTest cases).

## Next step
- OPS-2: set `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` + `INTERNAL_SERVICE_TOKEN` in VPS env for notification-service.
- cohorts-latest `InvitationService` must call this endpoint (with `X-Internal-Token` header) — see roadmap note under BE-5.
- PR-D: merge this branch to `development` after OPS review.

## Key decisions
- No Thymeleaf — simple `String.replace("{key}", value)` template substitution (design decision #10).
- Internal auth = `X-Internal-Token` header (not user JWT) — endpoint permitted in SecurityConfig, validated in controller.
- Log-only fallback when `spring.mail.host` absent: service starts without crashing; emails are logged at WARN.
- `fromAddress` defaults to `noreply@teclavya.com` when `spring.mail.username` is blank.
