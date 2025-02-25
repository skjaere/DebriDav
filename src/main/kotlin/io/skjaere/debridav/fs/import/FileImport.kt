package io.skjaere.debridav.fs.import

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "import_registry",
    indexes = [Index(name = "imported_files", columnList = "path")]
)
open class FileImport() {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    @Column(
        unique = true,
        length = 2048
    )
    open var path: String? = null

    constructor(path: String) : this() {
        this.path = path
    }
}
