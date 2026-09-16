-- V66 (WP-c / docs/35 cron parity): unreachable-retry ladder bookkeeping.
--
-- Hermes cron.retry_unreachable parity: a recurring job whose fire fails
-- with a transient network/DNS error BEFORE any model call used to sit out
-- a full period (a daily job fired into a reconnecting VPN silently skipped
-- a day). The scheduler now pulls next_run_at earlier along a bounded
-- 5/15/30-minute ladder, suppresses the interim failure notice while a
-- re-run is pending, and resets the ladder on any run that reaches the
-- model. One-shot jobs (repeat_count = 1) never re-run: at-most-once
-- dispatch accounting.

ALTER TABLE cron_jobs
    ADD COLUMN IF NOT EXISTS unreachable_retries INTEGER NOT NULL DEFAULT 0;
