-- S15: Curator snapshot/rollback — backup of skills state before mutations
CREATE TABLE curator_snapshots (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    skill_count INT DEFAULT 0,
    snapshot_data TEXT
);

CREATE INDEX idx_curator_snapshots_created ON curator_snapshots(created_at DESC);