ALTER TABLE file_chunk
    ADD CONSTRAINT uc_88499a76d31d470353e63b34c UNIQUE (remotely_cached_entity_id, start_byte, end_byte);