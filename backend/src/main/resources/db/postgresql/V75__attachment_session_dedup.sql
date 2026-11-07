-- Repeated content can belong to distinct sessions of the same owner.
DROP INDEX uq_attachment_owner_hash_disposition;
CREATE UNIQUE INDEX uq_attachment_owner_hash_disposition_session
    ON attachment_artifacts (owner_id, content_hash, disposition, session_id) NULLS NOT DISTINCT;
