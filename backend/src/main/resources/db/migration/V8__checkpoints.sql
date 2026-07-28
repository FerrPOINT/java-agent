-- V8: Create checkpoints table for filesystem rollback snapshots
CREATE TABLE checkpoints (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    description VARCHAR(512),
    file_count INTEGER NOT NULL DEFAULT 0,
    total_size_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    files_json TEXT
);

CREATE INDEX idx_checkpoints_created_at ON checkpoints(created_at);