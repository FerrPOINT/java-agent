-- H2 variant of V22: H2 has no partial indexes (WHERE clause in CREATE INDEX).
-- Plain composite indexes only; the partial variants run on PostgreSQL
-- (db/postgresql/V22). bot_sessions indexes are omitted: that table is a
-- legacy production object not created by any migration, so it does not
-- exist in H2 test databases.
CREATE INDEX IF NOT EXISTS idx_todos_user_status ON todos(user_id, status);
CREATE INDEX IF NOT EXISTS idx_messages_session_created ON messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_user_created ON usage_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_pending_user_status ON memory_pending(user_id, status);
CREATE INDEX IF NOT EXISTS idx_skills_archived ON skills(archived);
CREATE INDEX IF NOT EXISTS idx_cron_jobs_next_run ON cron_jobs(next_run_at);
