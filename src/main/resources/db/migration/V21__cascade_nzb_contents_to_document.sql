-- NzbContents has no meaning without its parent NzbDocument, so cascade-delete
-- when the document is removed. The previous NO ACTION constraint forced callers
-- to manually unlink contents before they could delete a document, which made
-- both production cleanup and integration-test teardown brittle.

ALTER TABLE nzb_contents DROP CONSTRAINT fk_nzb_contents_document;
ALTER TABLE nzb_contents
    ADD CONSTRAINT fk_nzb_contents_document
        FOREIGN KEY (nzb_document_id) REFERENCES nzb_document (id) ON DELETE CASCADE;
