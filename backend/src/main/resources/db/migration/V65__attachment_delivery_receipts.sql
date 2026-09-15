-- V65 (WP-11 tail / docs/35): outbound delivery receipt columns on
-- attachment_artifacts. When the bot delivers an artifact as a native
-- Telegram attachment it records the platform message id — a retry after
-- an ambiguous send checks this and never re-sends the same artifact.

ALTER TABLE attachment_artifacts
    ADD COLUMN IF NOT EXISTS delivered_message_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;

-- Only delivered artifacts may carry a receipt.
ALTER TABLE attachment_artifacts
    ADD CONSTRAINT chk_artifact_receipt_state CHECK (
        (delivered_message_id IS NULL AND delivered_at IS NULL)
        OR state = 'delivered'
    );
