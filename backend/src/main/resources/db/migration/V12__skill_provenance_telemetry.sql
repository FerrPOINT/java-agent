-- S6: Skill provenance — track write origin
ALTER TABLE skills ADD COLUMN IF NOT EXISTS write_origin VARCHAR(32) DEFAULT 'FOREGROUND';
-- S7: Skill usage telemetry
ALTER TABLE skills ADD COLUMN IF NOT EXISTS view_count INT DEFAULT 0;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS manage_count INT DEFAULT 0;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP;
-- S2: Curator lifecycle — archived state
ALTER TABLE skills ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
-- S12: Trust level for skills
ALTER TABLE skills ADD COLUMN IF NOT EXISTS trust_level VARCHAR(32) DEFAULT 'AGENT_CREATED';
-- S10: Cache token tracking for usage
ALTER TABLE usage_log ADD COLUMN IF NOT EXISTS cache_read_tokens INT DEFAULT 0;
ALTER TABLE usage_log ADD COLUMN IF NOT EXISTS cache_write_tokens INT DEFAULT 0;