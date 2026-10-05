ALTER TABLE memory_write_audit
    ADD COLUMN memory_id UUID;

CREATE INDEX idx_memory_write_audit_memory_id
    ON memory_write_audit(memory_id);
