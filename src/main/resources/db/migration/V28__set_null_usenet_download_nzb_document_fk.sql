-- Allow EmptyNzbDocumentCleanupTask to delete orphaned nzb_document rows (those
-- with no nzb_contents referencing them) without tripping the FK constraint on
-- usenet_download. Set the reference to NULL on parent delete instead of NO ACTION
-- — the usenet_download row is an audit/history record and doesn't need a live
-- pointer to a deleted document.
ALTER TABLE usenet_download
    DROP CONSTRAINT fk_usenet_download_nzb_document;
ALTER TABLE usenet_download
    ADD CONSTRAINT fk_usenet_download_nzb_document
    FOREIGN KEY (nzb_document_id) REFERENCES nzb_document(id) ON DELETE SET NULL;
