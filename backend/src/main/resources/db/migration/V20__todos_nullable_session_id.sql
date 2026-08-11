-- Make session_id nullable on todos to support global (non-session) kanban items.
ALTER TABLE todos ALTER COLUMN session_id DROP NOT NULL;