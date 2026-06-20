-- V3__lifecycle_send_gate_rollback.sql  (notification-service)
-- manual ops runbook artifact — Flyway Community does NOT auto-run it.
-- Execute manually against the target database when rolling back V3.
-- Run statements IN ORDER (reverse dependency: child tables before parent).
-- After executing this script, redeploy the service build that predates V3.

-- 1. Drop audit table (references lifecycle_message_review_queue via FK)
DROP TABLE IF EXISTS lifecycle_admin_audit;

-- 2. Drop analytics event table (references lifecycle_message_review_queue via FK)
DROP TABLE IF EXISTS lifecycle_event;

-- 3. Drop the review queue (parent of the two FKs above)
DROP TABLE IF EXISTS lifecycle_message_review_queue;

-- 4. Remove additive columns from notification_preference
--    (RC-1: remove entity fields quietHoursEnabled / timezone from NotificationPreference.java in the same rollback PR)
ALTER TABLE notification_preference DROP COLUMN IF EXISTS quiet_hours_enabled;
ALTER TABLE notification_preference DROP COLUMN IF EXISTS timezone;
