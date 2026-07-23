-- Align todos entity with JPA model
ALTER TABLE todos ADD COLUMN IF NOT EXISTS title TEXT;
ALTER TABLE todos ADD COLUMN IF NOT EXISTS priority TEXT;
UPDATE todos SET title = content WHERE title IS NULL;
