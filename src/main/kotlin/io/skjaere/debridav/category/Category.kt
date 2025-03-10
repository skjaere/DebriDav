package io.skjaere.debridav.category

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
open class Category() {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    open var id: Long? = null

    @Column(unique = true)
    open var name: String? = null

    open var downloadPath: String? = null

    constructor(name: String, downloadPath: String) : this() {
        this.name = name
        this.downloadPath = downloadPath
    }
}
