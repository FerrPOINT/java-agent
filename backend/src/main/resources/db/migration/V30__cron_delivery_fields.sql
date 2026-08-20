-- V30: Cron output delivery plumbing (Hermes parity: h75/h76).
-- last_run_session_id — the session a cron run produced, so the delivery poller
-- can read the run's assistant output; last_delivered_run_at — high-water mark
-- for the bot-side delivery poller, so each run's output is delivered exactly once.
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS last_run_session_id UUID;
ALTER TABLE cron_jobs ADD COLUMN IF NOT EXISTS last_delivered_run_at TIMESTAMPTZ;
