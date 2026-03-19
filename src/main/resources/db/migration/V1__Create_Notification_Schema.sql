CREATE TABLE IF NOT EXISTS notification (
    notification_id UUID PRIMARY KEY,
    student_id VARCHAR(255) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    body TEXT,
    metadata JSONB,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    is_sent BOOLEAN NOT NULL DEFAULT FALSE,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notification_student ON notification(student_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notification_unsent ON notification(is_sent, channel, created_at);

CREATE TABLE IF NOT EXISTS notification_preference (
    preference_id UUID PRIMARY KEY,
    student_id VARCHAR(255) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_hours_start INTEGER DEFAULT 22,
    quiet_hours_end INTEGER DEFAULT 7,
    CONSTRAINT uk_notification_pref UNIQUE (student_id, notification_type)
);

CREATE INDEX IF NOT EXISTS idx_notification_pref_student ON notification_preference(student_id);

CREATE TABLE IF NOT EXISTS push_subscription (
    subscription_id UUID PRIMARY KEY,
    student_id VARCHAR(255) NOT NULL,
    endpoint TEXT NOT NULL,
    p256dh_key TEXT,
    auth_key TEXT,
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_push_sub_student ON push_subscription(student_id, active);
