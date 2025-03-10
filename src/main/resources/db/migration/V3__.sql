CREATE SEQUENCE IF NOT EXISTS file_chunk_seq START WITH 1 INCREMENT BY 50;

DROP TABLE IF EXISTS file_chunk;

CREATE TABLE file_chunk
(
    id                        BIGINT NOT NULL,
    remotely_cached_entity_id BIGINT,
    debrid_provider           SMALLINT,
    last_accessed             TIMESTAMP WITHOUT TIME ZONE,
    start_byte                BIGINT,
    end_byte                  BIGINT,
    blob_id                   BIGINT,
    CONSTRAINT pk_filechunk PRIMARY KEY (id)
);

ALTER TABLE file_chunk
    ADD CONSTRAINT FK_FILECHUNK_ON_BLOB FOREIGN KEY (blob_id) REFERENCES blob (id);

ALTER TABLE file_chunk
    ADD CONSTRAINT FK_FILECHUNK_ON_REMOTELYCACHEDENTITY FOREIGN KEY (remotely_cached_entity_id) REFERENCES db_item (id);

ALTER TABLE category
    ADD CONSTRAINT uniqueCatgegoryName UNIQUE (name);
