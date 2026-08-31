-- V36: Multi-user — add user_id to audit_log for per-user audit trails.
-- audit_log currently records session_id but not user_id. For multi-user
-- audit trails, we need to know which user triggered each audit event.
-- userId is nullable — existing rows get NULL and are treated as system-level.

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS user_id TEXT;
CREATE INDEX IF NOT EXISTS idx_audit_log_user_id ON audit_log(user_id);