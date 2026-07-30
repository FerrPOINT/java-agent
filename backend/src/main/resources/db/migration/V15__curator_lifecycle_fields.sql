-- S5: Curator — skill lifecycle state, pinned flag, absorbed_into declaration
ALTER TABLE skills ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(20) DEFAULT 'active';
ALTER TABLE skills ADD COLUMN IF NOT EXISTS pinned BOOLEAN DEFAULT false;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS absorbed_into VARCHAR(255);

-- S8: Curator backup — manifest column for snapshot metadata
ALTER TABLE curator_snapshots ADD COLUMN IF NOT EXISTS manifest TEXT;