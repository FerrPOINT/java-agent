-- M15: align remaining naive-TIMESTAMP columns to TIMESTAMPTZ.
-- V12/V27 shipped TIMESTAMP (without time zone) while every other table uses
-- TIMESTAMPTZ; mixed types break ordering/comparisons across tables and
-- silently drop timezone info. Migrations already applied are immutable —
-- this follow-up converts the column types in place (USING is a no-op cast).
ALTER TABLE skills
    ALTER COLUMN last_activity_at TYPE timestamptz;
ALTER TABLE cron_jobs
    ALTER COLUMN last_error_at TYPE timestamptz;
ALTER TABLE cron_execution_log
    ALTER COLUMN started_at TYPE timestamptz,
    ALTER COLUMN finished_at TYPE timestamptz,
    ALTER COLUMN created_at TYPE timestamptz;
ALTER TABLE skill_audit_log
    ALTER COLUMN timestamp TYPE timestamptz;
