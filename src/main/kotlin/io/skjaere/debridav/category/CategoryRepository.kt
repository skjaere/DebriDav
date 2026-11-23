package io.skjaere.debridav.category

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.transaction.annotation.Transactional

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByNameIgnoreCase(name: String): Category?

    /**
     * Race-safe get-or-create. Resolves the TOCTOU between a `findByName` check and a
     * subsequent `save` that bursts of concurrent torrent/NZB adds were losing on the
     * `category_unique_name` constraint. The `DO UPDATE SET name = EXCLUDED.name` is a
     * no-op write that exists only to make `RETURNING *` produce a row on conflict —
     * Postgres skips RETURNING for plain `DO NOTHING`.
     */
    @Transactional
    @Query(
        value = """
            INSERT INTO category (id, name, download_path)
            VALUES (nextval('category_seq'), :name, :downloadPath)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING *
        """,
        nativeQuery = true,
    )
    fun upsertByName(name: String, downloadPath: String): Category
}
