-- Final todos schema alignment: content migrated to title
ALTER TABLE todos DROP COLUMN IF EXISTS content;
ALTER TABLE todos ALTER COLUMN title SET NOT NULL;
