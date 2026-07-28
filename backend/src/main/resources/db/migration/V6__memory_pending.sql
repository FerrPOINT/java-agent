-- V6: Create memory_pending table for write-approval gate
CREATE TABLE memory_pending (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target VARCHAR(16) DEFAULT 'memory',
    content TEXT,
    old_text VARCHAR(4096),
    summary VARCHAR(512),
    origin VARCHAR(32) DEFAULT 'foreground',
    status VARCHAR(16) DEFAULT 'pending',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    resolved_at TIMESTAMPTZ
);