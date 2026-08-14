-- V25: Add context_from column for cron job chaining (parity with Hermes context_from).
-- Stores comma-separated upstream cron job IDs whose output should be
-- injected as context into the downstream job's prompt at execution time.
ALTER TABLE cron_jobs ADD COLUMN context_from TEXT;