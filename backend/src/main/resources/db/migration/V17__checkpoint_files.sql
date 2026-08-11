-- V17: Create checkpoint_files table to store file contents for actual rollback restoration.
-- Each row stores the Base64-encoded content of a single file at checkpoint time.
CREATE TABLE checkpoint_files (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    checkpoint_id UUID NOT NULL REFERENCES checkpoints(id) ON DELETE CASCADE,
    file_path VARCHAR(4096) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    content_base64 TEXT,
    CONSTRAINT uk_checkpoint_file_path UNIQUE (checkpoint_id, file_path)
);

CREATE INDEX idx_checkpoint_files_checkpoint_id ON checkpoint_files(checkpoint_id);