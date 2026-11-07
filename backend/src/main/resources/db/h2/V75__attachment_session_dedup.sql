DROP INDEX uq_attachment_owner_hash_disposition;
CREATE UNIQUE NULLS NOT DISTINCT INDEX uq_attachment_owner_hash_disposition_session
    ON attachment_artifacts (owner_id, content_hash, disposition, session_id);
