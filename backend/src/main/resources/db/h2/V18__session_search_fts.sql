-- H2 override for V18: H2 has no tsvector/GIN (PostgreSQL-only FTS).
-- No-op stub — SessionSearchService falls back to LIKE-based search on H2.
SELECT 1;
