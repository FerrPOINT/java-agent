-- Add missing FK constraints
ALTER TABLE sessions ADD CONSTRAINT fk_sessions_parent FOREIGN KEY (parent_session_id) REFERENCES sessions(id) ON DELETE SET NULL;
ALTER TABLE usage_log ADD CONSTRAINT fk_usage_log_session FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE;
-- audit_log.session_id is TEXT not UUID, can't add FK — skip it