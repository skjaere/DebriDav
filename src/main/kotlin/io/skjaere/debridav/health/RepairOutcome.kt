package io.skjaere.debridav.health

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant

enum class RepairAction {
    REPAIRED,
    DELETED,
    SKIPPED
}

@Entity
@Table(name = "repair_outcome")
open class RepairOutcome {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "queue_name", nullable = false)
    open var queueName: String? = null

    @Column(name = "msg_id", nullable = false)
    open var msgId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    open var action: RepairAction? = null

    @Column(name = "created_at", nullable = false)
    open var createdAt: Instant = Instant.now()
}

@Repository
interface RepairOutcomeRepository : CrudRepository<RepairOutcome, Long>
