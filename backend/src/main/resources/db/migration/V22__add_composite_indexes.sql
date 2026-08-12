CREATE INDEX IF NOT EXISTS idx_todos_user_status ON todos(user_id, status);
CREATE INDEX IF NOT EXISTS idx_messages_session_created ON messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_user_created ON usage_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_pending_user_status ON memory_pending(user_id, status);
CREATE INDEX IF NOT EXISTS idx_skills_archived ON skills(archived) WHERE archived = false;
CREATE INDEX IF NOT EXISTS idx_cron_jobs_next_run ON cron_jobs(next_run_at) WHERE enabled = true;
CREATE INDEX IF NOT EXISTS idx_bot_sessions_user_active ON bot_sessions(user_id, active) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_bot_sessions_chat_active ON bot_sessions(chat_id, active) WHERE active = true;