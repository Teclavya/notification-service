-- V5__create_oncall_and_processed_events_tables.sql (notification-service)
-- Issue #1799: Multi-Channel SLA Monitoring & On-Call Escalation Engine
-- Stores idempotency ledger for processed RabbitMQ CloudEvents and audit logs for on-call webhook/email dispatches.

CREATE TABLE IF NOT EXISTS processed_ticket_events (
    event_id        VARCHAR(64)  PRIMARY KEY,
    event_type      VARCHAR(64)  NOT NULL,
    ticket_number   VARCHAR(32)  NOT NULL,
    processed_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_processed_ticket_events_ticket ON processed_ticket_events(ticket_number);

CREATE TABLE IF NOT EXISTS oncall_dispatch_audit (
    id                  UUID         PRIMARY KEY,
    ticket_number       VARCHAR(32)  NOT NULL,
    event_type          VARCHAR(64)  NOT NULL,
    channel             VARCHAR(32)  NOT NULL,
    recipient_or_url    VARCHAR(512),
    status              VARCHAR(32)  NOT NULL,
    error_message       TEXT,
    dispatched_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oncall_dispatch_audit_ticket ON oncall_dispatch_audit(ticket_number, dispatched_at DESC);
