-- V5: Add target column to memory table for two-store model (memory + user)
ALTER TABLE memory ADD COLUMN target VARCHAR(16) DEFAULT 'memory';