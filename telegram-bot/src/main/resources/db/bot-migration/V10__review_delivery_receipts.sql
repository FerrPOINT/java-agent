-- A Telegram review notification may be accepted while the backend acknowledgement fails.
-- Keep its immutable backend delivery id locally so a later poll retries only acknowledgement.
CREATE TABLE review_delivery_receipts (
    id UUID PRIMARY KEY,
    backend_session_id UUID NOT NULL,
    backend_delivery_id UUID NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_review_delivery_receipts_delivery UNIQUE (backend_session_id, backend_delivery_id)
);

CREATE INDEX idx_review_delivery_receipts_session
    ON review_delivery_receipts(backend_session_id, delivered_at DESC);
