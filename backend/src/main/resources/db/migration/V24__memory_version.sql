-- V24: Add version column for optimistic locking (drift detection parity with Hermes).
-- Hermes detects external drift via round-trip mismatch + entry-size overflow on file writes.
-- In DB-backed memory, @Version + OptimisticLockException provides equivalent protection:
-- concurrent modifications from other sessions/tools are detected automatically.
ALTER TABLE memory ADD COLUMN version BIGINT NOT NULL DEFAULT 0;