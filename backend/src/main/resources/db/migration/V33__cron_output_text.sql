-- Hermes parity: store cron job output text for context_from chaining.
-- Hermes stores cron output in ~/.hermes/cron/output/<job_id>/*.md files;
-- java-agent stores it in the DB for transactional integrity.
ALTER TABLE cron_execution_log ADD COLUMN IF NOT EXISTS output_text TEXT;