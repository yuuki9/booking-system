package com.booking.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

@Entity
@Table(name = "events")
class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    val capacity: Int,

    @Column(name = "reserved_count", nullable = false)
    var reservedCount: Int = 0,

    @Version
    var version: Long = 0,
) {
    val remainingCapacity: Int
        get() = capacity - reservedCount
}
