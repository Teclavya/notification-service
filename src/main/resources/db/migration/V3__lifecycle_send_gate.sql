-- V3__lifecycle_send_gate.sql  (notification-service)
-- Additive-only migration: adds lifecycle send-gate tables + 2 preference columns.
-- No drops, no renames, no NOT NULL additions on existing columns without a DEFAULT.
-- Status values (app-level VARCHAR, no PG enum): DRAFTED | SAFETY_CHECKED |
--   AWAITING_VETO_WINDOW | APPROVED | DEFERRED | SENT | VETOED | SAFETY_REJECTED
-- Tier values:  AMBER (P1 only) | RED (reserved P2)
-- Safety verdict: PASS | FAIL | NULL (null = not yet evaluated)
-- IMPORTANT (RC-1): NotificationPreference.java MUST gain quietHoursEnabled / timezone
--   fields in the same PR — ddl-auto=validate will crash the service at boot otherwise.

-- ---------------------------------------------------------------------------
-- Table: lifecycle_message_review_queue
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_message_review_queue (
    id                      UUID         PRIMARY KEY,
    student_id              VARCHAR(255) NOT NULL,
    notification_type       VARCHAR(50)  NOT NULL,
    template_id             VARCHAR(255),
    message_body            TEXT         NOT NULL,
    tier                    VARCHAR(10)  NOT NULL DEFAULT 'AMBER',   -- AMBER (P1); RED reserved P2
    status                  VARCHAR(32)  NOT NULL DEFAULT 'DRAFTED', -- 8 P1 states (see above)
    safety_verdict          VARCHAR(8),                              -- PASS | FAIL | NULL
    safety_details          TEXT,
    veto_window_expires_at  TIMESTAMP,                               -- NULL until SAFETY_CHECKED(PASS)
    deferred_until          TIMESTAMP,                               -- set when DEFERRED (quiet hours)
    timezone                VARCHAR(64),                             -- AUTHORITATIVE student tz from enqueue metadata
    metadata                JSONB,                                   -- {pathId, topicId, lastActiveAt, channels}
    veto_reason             TEXT,
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    sent_at                 TIMESTAMP
);

-- Poller hot path: due veto windows (AWAITING_VETO_WINDOW rows with expired window)
CREATE INDEX IF NOT EXISTS idx_lmrq_awaiting_expiry
    ON lifecycle_message_review_queue(status, veto_window_expires_at);

-- Poller hot path: due deferrals (DEFERRED rows past deferred_until)
CREATE INDEX IF NOT EXISTS idx_lmrq_deferred
    ON lifecycle_message_review_queue(status, deferred_until);

-- Admin list: primary sort/filter pattern (status filter + newest-first)
CREATE INDEX IF NOT EXISTS idx_lmrq_admin_list
    ON lifecycle_message_review_queue(status, created_at DESC);

-- Per-student lookup (admin drill-down by student)
CREATE INDEX IF NOT EXISTS idx_lmrq_student
    ON lifecycle_message_review_queue(student_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Table: lifecycle_event  (analytics)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_event (
    id           UUID         PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,   -- e.g. 'lifecycle_message.sent', 'feature_flag.updated'
    message_id   UUID,                    -- FK → lifecycle_message_review_queue(id); nullable for flag/pref events
    student_id   VARCHAR(255),
    properties   JSONB,                   -- PII whitelist: only opaque studentId; no name/email
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_lifecycle_event_msg FOREIGN KEY (message_id)
        REFERENCES lifecycle_message_review_queue(id) ON DELETE SET NULL
);

-- Query by event type + time window (analytics queries, spec AC-7.1)
CREATE INDEX IF NOT EXISTS idx_lifecycle_event_type_time
    ON lifecycle_event(event_type, created_at DESC);

-- FK lookup (cascade/set-null queries)
CREATE INDEX IF NOT EXISTS idx_lifecycle_event_msg
    ON lifecycle_event(message_id);

-- Per-student analytics (FIX RC-2: would otherwise seq-scan lifecycle_event per student)
CREATE INDEX IF NOT EXISTS idx_lifecycle_event_student
    ON lifecycle_event(student_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Additive columns on existing notification_preference table
-- GAP-7 prerequisites: quiet-hours enforcement needs enabled flag + timezone fallback.
-- ADD COLUMN IF NOT EXISTS is safe on PostgreSQL 9.6+ — never removes existing data.
-- RC-1: entity fields quietHoursEnabled / timezone MUST land in the same PR.
-- ---------------------------------------------------------------------------
ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS quiet_hours_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE notification_preference
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);   -- nullable; FALLBACK only (enqueue metadata is authoritative)

-- ---------------------------------------------------------------------------
-- Table: lifecycle_admin_audit
-- Every admin action (VIEWED|APPROVED|VETOED|EDITED) and every send-block is recorded.
-- FK is ON DELETE SET NULL so the audit row survives a GDPR queue-row purge (FIX RC-3).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_admin_audit (
    id            UUID         PRIMARY KEY,
    message_id    UUID,
    admin_user_id VARCHAR(255),
    action        VARCHAR(32)  NOT NULL,   -- VIEWED|APPROVED|VETOED|EDITED|SEND_BLOCKED
    detail        TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_audit_msg FOREIGN KEY (message_id)
        REFERENCES lifecycle_message_review_queue(id) ON DELETE SET NULL
);

-- Admin audit lookup by message (most common access pattern)
CREATE INDEX IF NOT EXISTS idx_lifecycle_audit_msg
    ON lifecycle_admin_audit(message_id, created_at DESC);
