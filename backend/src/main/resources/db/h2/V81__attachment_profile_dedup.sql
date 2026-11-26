-- An existing session is the authority for its owner's attached data.
UPDATE attachment_artifacts artifact
SET profile = (SELECT session.profile FROM sessions session
               WHERE session.id = artifact.session_id AND session.user_id = artifact.owner_id)
WHERE EXISTS (SELECT 1 FROM sessions session
              WHERE session.id = artifact.session_id AND session.user_id = artifact.owner_id
                AND session.profile <> artifact.profile);

DROP INDEX uq_attachment_owner_hash_disposition_session;
CREATE UNIQUE NULLS NOT DISTINCT INDEX uq_attachment_profile_dedup
    ON attachment_artifacts (owner_id, profile, content_hash, disposition, session_id, dedup_scope);
