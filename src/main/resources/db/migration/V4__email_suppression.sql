-- V4__email_suppression.sql (notification-service)
-- Additive migration: email bounce suppression tracking + send event idempotency ledger.
-- Ticket #1309 / G23 (Enterprise B2B College Vertical).

CREATE TABLE IF NOT EXISTS email_suppression (
    id                  UUID         PRIMARY KEY,
    email               VARCHAR(320) NOT NULL,
    suppression_type    VARCHAR(20)  NOT NULL,  -- 'HARD_BOUNCE' | 'UNSUBSCRIBE' | 'COMPLAINT'
    suppressed_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    source              VARCHAR(100),           -- 'SMTP_BOUNCE_WEBHOOK' | 'STUDENT_OPT_OUT' | 'ADMIN_MANUAL'
    message_id          UUID,
    notes               TEXT,
    removed_at          TIMESTAMP,              -- NULL = active suppression; non-NULL = suppression lifted
    CONSTRAINT ux_email_suppression_email UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_email_suppression_email ON email_suppression(email);

CREATE TABLE IF NOT EXISTS email_send_event (
    id                  UUID         PRIMARY KEY,
    to_email            VARCHAR(320) NOT NULL,
    subject             VARCHAR(500),
    template_id         VARCHAR(100),
    idempotency_key     VARCHAR(255) NOT NULL,   -- caller-supplied; prevents double-send on retry
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED | SUPPRESSED
    attempt_count       INTEGER      NOT NULL DEFAULT 1,
    soft_bounce_count   INTEGER      NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255),            -- SMTP Message-ID header for delivery tracing
    error_detail        TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMP,
    CONSTRAINT ux_email_send_event_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_email_send_event_to_created ON email_send_event(to_email, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_send_event_status ON email_send_event(status, created_at DESC);
