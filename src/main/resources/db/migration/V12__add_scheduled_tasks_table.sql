CREATE TABLE scheduled_tasks (
    task_name TEXT NOT NULL,
    task_instance TEXT NOT NULL,
    task_data BYTEA,
    execution_time TIMESTAMP WITH TIME ZONE NOT NULL,
    picked BOOLEAN NOT NULL,
    picked_by TEXT,
    last_success TIMESTAMP WITH TIME ZONE,
    last_failure TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX idx_scheduled_tasks_execution_time ON scheduled_tasks (execution_time);
