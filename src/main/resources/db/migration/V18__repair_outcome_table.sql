CREATE TABLE repair_outcome (
    id BIGSERIAL PRIMARY KEY,
    queue_name VARCHAR(255) NOT NULL,
    msg_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_repair_outcome_queue_msg ON repair_outcome (queue_name, msg_id);
