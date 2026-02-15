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

DROP TABLE IF EXISTS debrid_usenet_contents;

CREATE TABLE nzb_contents (
    id                      BIGINT NOT NULL,
    debrid_links            JSONB,
    mime_type               VARCHAR(255),
    modified                BIGINT,
    original_path           VARCHAR(255),
    size                    BIGINT,
    nzb_document_id         BIGINT,
    nzb_streamable_file_id  BIGINT,
    CONSTRAINT nzb_contents_pkey PRIMARY KEY (id),
    CONSTRAINT fk_nzb_contents_document FOREIGN KEY (nzb_document_id) REFERENCES nzb_document (id) ON DELETE NO ACTION,
    CONSTRAINT fk_nzb_contents_streamable_file FOREIGN KEY (nzb_streamable_file_id) REFERENCES nzb_streamable_file (id) ON DELETE NO ACTION
);
