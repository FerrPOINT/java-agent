-- WP-1 cutover: cron delivery runs exclusively on the durable delivery ledger.
-- The bot-side high-water mark (last_delivered_run_at) is superseded by ledger
-- state (pending → claimed → delivered) and is no longer written; drop it.
ALTER TABLE cron_jobs DROP COLUMN IF EXISTS last_delivered_run_at;

-- Hermes delivery_ledger retention parity: terminal rows are kept for a week
-- of inspection, then pruned. Delivered rows older than 7 days go now; the
-- sweeper keeps the bound going forward.
DELETE FROM delivery_work_items
 WHERE state = 'delivered'
   AND delivered_at < now() - interval '7 days';
