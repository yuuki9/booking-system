package com.lab.reservation.domain

enum class LockStrategy {
    NONE,
    OPTIMISTIC,
    PESSIMISTIC,
    REDIS,
    ;

    companion object {
        fun from(value: String?): LockStrategy? =
            value?.trim()?.uppercase()?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
