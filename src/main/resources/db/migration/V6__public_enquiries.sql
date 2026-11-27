-- #2519: Public Contact Us enquiry audit table (additive, zero-downtime)
CREATE TABLE IF NOT EXISTS public_enquiries (
    id              UUID PRIMARY KEY,
    reference_id    VARCHAR(32)  NOT NULL UNIQUE,
    topic           VARCHAR(32)  NOT NULL,
    target_alias    VARCHAR(128) NOT NULL,
    sender_name     VARCHAR(100) NOT NULL,
    sender_email    VARCHAR(254) NOT NULL,
    message_body    TEXT         NOT NULL,
    client_ip_hash  VARCHAR(64),
    status          VARCHAR(24)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_public_enquiries_created_at
    ON public_enquiries (created_at DESC);
