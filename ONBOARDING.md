# Onboarding — `notification-service`

> **Goal of this doc:** get you from `git clone` to a **reviewed pull request on your first day**. Read it top-to-bottom once (~20 min), do the "Day-1 first PR" at the end, and you'll understand how this service is wired.
>
> **What this service is:** the platform's **multi-channel notification & nudge engine** — in-app notifications, transactional email (via SMTP/JavaMailSender), quiet-hours + per-day caps, a Redis pub/sub bridge that feeds the AIChatbot WebSocket hub, and an "ethical send-gate" lifecycle pipeline that content-safety-checks and human-reviews lifecycle nudges before they go out.

**Stack in one line:** Java 17 + Spring Boot 3.4, Postgres + Flyway (`ddl-auto: validate`), Spring Security (JWT via the shared `SecretGuard`), Redis (pub/sub), Spring Mail, Resilience4j. Maven (no wrapper). Email only — **no Twilio/SMS, no Teams, no RabbitMQ**.

---

## 1. The setup

**Prerequisites:** JDK **17**, a system **Maven** (there is **no `mvnw` wrapper**), a **PostgreSQL**, and **GitHub Packages credentials** in `~/.m2/settings.xml` (depends on `com.teclavya:teclavya-platform-security` — the shared `SecretGuard` — from the `github-teclavya` package repo).

**Minimum to boot the default `dev` profile:** a reachable Postgres. The dev profile bakes a dev `JWT_SECRET`, Redis, and DB password, so it boots without you setting secrets locally. (Redis is configured but not required just to start.)

```bash
# start Postgres (DB name: teclavya_notifications)
docker run -d --name ns-pg -e POSTGRES_DB=teclavya_notifications -e POSTGRES_PASSWORD=Kurichedu12345! -p 5432:5432 postgres:16

mvn spring-boot:run          # default port 9016 (application-dev.yaml)
```
Health check: `GET http://localhost:9016/actuator/health`.

> ⚠️ **`.env.example` has a variable-name bug — trust the code.** It lists `APPLICATION_JWT_SECRET=CHANGE_ME`, but the app actually reads **`JWT_SECRET`** (`application.jwt.secret: ${JWT_SECRET}`). Setting the documented `APPLICATION_JWT_SECRET` has **zero effect**. There is also **no `application-prod.yaml`** — if you run with `APP_PROFILE=prod`, Spring loads no datasource/JWT/CORS config at all (the `.env.example` itself flags this gap). Locally, stick with the `dev` profile.

---

## 2. How to think about this service (mental model)

```
Direct send:     POST /api/v1/notifications/internal/**  (s2s, network-policy auth)
                 POST /api/v1/notifications/email         (X-Internal-Token header)
   controller → NotificationService/EmailService → repo → Postgres
                                              ↘ NotificationPublisher → Redis pub/sub → AIChatbot WS hub

Ethical send-gate (lifecycle nudges, flag-gated):
   enqueue → lifecycle_message_review_queue → LifecycleSendGatePoller (@Scheduled)
        → ContentSafetyVerifier (Resilience4j, fail-closed) → approve → send
   gated by FF_LIFECYCLE_SEND_GATE_ENABLED (default OFF)
```

Two things to internalize:
1. **Two very different auth models on the internal endpoints.** `/api/v1/notifications/internal/**` has **no credential check at all** — it's protected purely by network topology (VPS-internal only). `/api/v1/notifications/email` is gated by a **static `X-Internal-Token` header** (not a JWT). End-user endpoints use a real JWT. Know which is which before you touch one — see §6.
2. **The lifecycle send-gate is the interesting subsystem.** Nudges aren't sent directly; they're enqueued, content-safety-verified (a Resilience4j-wrapped, fail-closed check), and human-reviewable before a scheduled poller sends them. It's all behind `FF_LIFECYCLE_SEND_GATE_ENABLED` (default OFF).

---

## 3. Configuration & secrets

Config: `src/main/resources/application.yml` (base) + `application-dev.yaml` + `application-test.yaml` (H2). Profile via `APP_PROFILE` (default `dev`). **No `application-prod.yaml` exists.**

| Env var | Behavior | Notes |
|---|---|---|
| `JWT_SECRET` | **Base `application.yml` has NO default** — `SecretGuard.requireStrong` fails the boot at `@PostConstruct` if unset/blank/placeholder/weak/known-compromised. (dev/test yamls carry their own dev fallbacks) | Read as `application.jwt.secret` |
| `INTERNAL_SERVICE_TOKEN` | Static token gating `/api/v1/notifications/email` via `X-Internal-Token` | Fails closed (rejects) if blank |
| `DB_HOST/PORT/NAME/USER/PASSWORD` | dev defaults to `teclavya_notifications` | dev has a hardcoded dev password fallback |
| `FF_LIFECYCLE_SEND_GATE_ENABLED` | default `false` (dark) | Gates the whole send-gate pipeline |
| `SPRING_MAIL_*` / `MAIL_*` | SMTP (JavaMailSender) | dev sets `notification.email.force-log-only: true` — emails are logged, not sent |

> Note: `application-dev.yaml` commits real-looking dev fallback literals (DB password, a Redis Cloud host/password, dev JWT secrets). They're scoped as non-prod dev defaults — **never hardcode a prod secret**, and the base `application.yml` correctly fails loud without `JWT_SECRET`.

---

## 4. Directory layout (`src/main/java/com/teclavya/notification/`)

| Package | What lives here |
|---|---|
| `controller/` | `NotificationController` (student-facing `/api/v1/notifications/**`), `InternalNotificationController` (s2s `/internal/**`) |
| `dto.request/` + `dto.response/` | Inbound/outbound DTOs |
| `entities/` | JPA entities/enums (`Notification`, `NotificationChannel`, `NotificationPreference`, `NotificationType`, `PushSubscription`) — note it's `entities`, not `model` |
| `service/` (+ `impl/`) | `NotificationService`, `EmailService`, `QuietHoursEvaluator`, `EmailTemplateRenderer` |
| `lifecycle.*` | The ethical send-gate: `analytics/` (event logging), `entity/` + `repo/` (review queue), `service/` (`LifecycleQueueService`), `verifier/` (`ContentSafetyVerifier`, Resilience4j) |
| `publisher/` | `NotificationPublisher` — Redis pub/sub to `notifications:{studentId}` for the AIChatbot WS hub |
| `scheduler/` | `LifecycleSendGatePoller` (verify/approve/send), `NotificationCleanupScheduler` |
| `security/` | `SecurityConfig`, `JwtAuthenticationFilter`, `JwtUtil` |
| `config/` | `MailConfig`, `NsLifecycleFeatureFlags`, `WebConfig` (CORS) |

There is **no `mapper` package**. Tests mirror the tree under `src/test/java/...`.

---

## 5. Request flow

**Email send** (`POST /api/v1/notifications/email`): permitAll in Security, but the controller checks the `X-Internal-Token` header against `INTERNAL_SERVICE_TOKEN` (401 if mismatch/blank) → `EmailService` → `EmailTemplateRenderer` → JavaMailSender (or log-only in dev).

**In-app notification**: created via the service, persisted, and **published to Redis pub/sub** (`NotificationPublisher`) on channel `notifications:{studentId}` so the AIChatbot WebSocket hub can push it live.

**Lifecycle send-gate** (flag ON): a nudge is enqueued to `lifecycle_message_review_queue`; `LifecycleSendGatePoller` (`@Scheduled`) runs it through `ContentSafetyVerifier` (Resilience4j circuit-breaker + time-limiter, **fail-closed**), approves, and sends.

---

## 6. Security / auth model

`security/SecurityConfig.java` (stateless, CSRF off):
- **permitAll:** `/actuator/**`, `/api/v1/notifications/internal/**`, `/api/v1/notifications/email`.
- **`.anyRequest().authenticated()`** — all student-facing `/api/v1/notifications/{studentId}/...` endpoints require a JWT.
- **`JwtUtil`**: `SecretGuard.requireStrong(...)` at `@PostConstruct` (fail-loud), then `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))` — the secret is **base64-decoded** and the HMAC algorithm is chosen by decoded key length (never raw UTF-8 + hardcoded HS256). `JwtAuthenticationFilter` sets the principal to the `userId` claim with **no authorities**.
- **The two internal endpoints are NOT credential-authenticated the same way:**
  - `/api/v1/notifications/internal/**` → **no token check at all** — "protected by network policy (VPS-internal only)." Auth-by-topology.
  - `/api/v1/notifications/email` → static `X-Internal-Token` header vs `INTERNAL_SERVICE_TOKEN`.
- **Secret map:** `JWT_SECRET` → user JWTs; `INTERNAL_SERVICE_TOKEN` → the one email endpoint (a shared static token, not a JWT). There is no mesh-JWT verification path here.

---

## 7. Database & migrations

- **Flyway**, files in `src/main/resources/db/migration/V<n>__<desc>.sql`. **Current highest is V3 — your next is V4.** `ddl-auto: validate` in dev (schema is Flyway-owned); the test profile runs H2 with Flyway **disabled** (`create-drop`).
- **The rule:** never edit an applied migration — add a new forward `V4`. Rollbacks live correctly in a **separate `db/rollback/`** dir (e.g. `V3__..._rollback.sql`), never in `db/migration/`. Run **`migration-collision-check`** before merge.

---

## 8. Testing & the real gate

- **The pom has NO surefire/failsafe/jacoco configuration** — tests run on default Surefire (`*Test.java`/`*Tests.java`), and there's no separate integration phase, so `mvn test` and `mvn verify` run the same set. Files named `*IntegrationTest.java` (e.g. `GatedSendEnqueueIntegrationTest`) still match `*Test.java`, so they run under `mvn test`. **No Testcontainers** — integration tests use H2 (`MODE=PostgreSQL`).
- ⚠️ **The PR template says `mvn verify -Dgroups="p0,p1"` but the pom has no JUnit-tag config** to honor `-Dgroups`, so it won't scope anything. Just run **`mvn test`**.
- **A new test class under `src/test/java` is auto-discovered** as long as its name ends in `Test`/`Tests` — no tag or `-Dgroups` needed. **CI:** PRs to `development`.

---

## 9. Conventions a PR must follow

- **Target branch: `development`.** Feature branches: `feature/<name>`.
- Thin controllers → `service/impl` → `repo` → `entities`. DTOs in `dto.request`/`dto.response`, never return an entity.
- **Feature-gated** behavior (the lifecycle send-gate) stays behind `feature.flags.lifecycle.*` (default off).
- **Errors:** validation via Jakarta `@Valid` (the controllers already use it); let the framework/handler map them.
- Best-effort side-channels (the Redis publish, analytics logging) must not fail the primary send — keep them swallow-and-log. Use `@Slf4j`; never log secrets.

---

## 10. Your Day-1 first PR (pick one)

Both safe, non-migration, non-security:

1. **Add validation to `dto/request/UpdatePreferencesRequest.java` + a test (strongest candidate).** That DTO is `@Valid`-checked at `NotificationController.updatePreference` but has **zero constraints** — notably no `@Min(0) @Max(23)` on `quietHoursStart`/`quietHoursEnd` (hours-of-day) and no `@NotBlank` on `notificationType`, so an out-of-range value like `99` passes silently today. Add the constraints + a MockMvc test asserting a 400. Files: the DTO + one test.
2. **Fix the `.env.example` variable-name bug** — it lists `APPLICATION_JWT_SECRET` but the app reads `JWT_SECRET` (setting the documented one does nothing). Correct it (and note the missing `application-prod.yaml`). Pure docs, high onboarding value.

**Avoid for a first PR:** `security/`, the `lifecycle.*` send-gate pipeline, `db/migration/`, and the Redis publisher.

### The workflow
```bash
git checkout development && git pull
git checkout -b feature/<your-short-name>
# ...make your change + tests...
mvn test                   # the real local gate (not -Dgroups)
git add -p && git commit -m "feat: validate UpdatePreferencesRequest quiet-hours range"
git push -u origin feature/<your-short-name>
# open a PR into `development`, fill the template, request review
```
**Definition of done:** `mvn test` green, PR template filled, `migration-collision-check` run if you touched migrations.

---

## 11. Landmines (things that will waste your afternoon)

| Landmine | What happens | What to do |
|---|---|---|
| **`.env.example` wrong var name** | `APPLICATION_JWT_SECRET` does nothing; app reads `JWT_SECRET` | Set `JWT_SECRET`; fix the example (first-PR #2) |
| **No `application-prod.yaml`** | `APP_PROFILE=prod` loads no datasource/JWT/CORS | Use `dev` locally; prod config is a real gap |
| **`JWT_SECRET` fail-loud** | Base profile won't boot without a strong secret (`SecretGuard`) | Set a strong non-placeholder value |
| **`/internal/**` has no auth** | Protected only by network topology | Don't expose it publicly; don't assume a token check exists |
| **`-Dgroups` not honored** | `mvn verify -Dgroups=…` doesn't scope | Run plain `mvn test` |
| **No Twilio/Teams/RabbitMQ** | Don't look for them | Email = SMTP/JavaMailSender; live push = Redis pub/sub |
| **dev emails are log-only** | `force-log-only: true` in dev — nothing is actually sent | Expected; check the logs |

---

## 12. Where to go next
- **`.env.example`** (with the caveat above) and `docs/features/`. The workspace **`../CLAUDE.md`** governance.
- **`migration-collision-check`** / **`p0-p1-staging-verify`** skills.
- **Ask.** Your onboarding buddy covers the last 20%.

---

_Found something wrong or out of date here? Fix it in the same PR as the code that changed it — this doc is only useful if it stays true._
