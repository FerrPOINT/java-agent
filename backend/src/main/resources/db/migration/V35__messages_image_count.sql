-- Preserve inline image metadata from OpenAI-compatible multimodal turns.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS image_count INTEGER NOT NULL DEFAULT 0;
