-- Durable review notifications form a FIFO queue until their delivery acknowledgement.
CREATE TABLE pending_review_summaries (
    delivery_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pending_review_summaries_session_created
    ON pending_review_summaries(session_id, created_at, delivery_id);
