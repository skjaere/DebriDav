CREATE SEQUENCE IF NOT EXISTS file_chunk_seq START WITH 1 INCREMENT BY 50;


CREATE TABLE file_chunk
(
    id            BIGINT        NOT NULL,
    url           VARCHAR(2048) NOT NULL,
    last_accessed TIMESTAMP WITHOUT TIME ZONE,
    start_byte    BIGINT,
    end_byte      BIGINT,
    blob_id       BIGINT,
    CONSTRAINT pk_filechunk PRIMARY KEY (id)
);

ALTER TABLE file_chunk
    ADD CONSTRAINT FK_FILECHUNK_ON_BLOB FOREIGN KEY (blob_id) REFERENCES blob (id);

