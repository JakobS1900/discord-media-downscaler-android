package com.jakob.dmd.domain.model

enum class Tier(val label: String, val limitBytes: Long) {
    ULTRA_SAFE("10 MB (Ultra-safe)", 10L * 1024 * 1024),
    FREE("25 MB (Free)", 25L * 1024 * 1024),
    NITRO_BASIC("50 MB (Nitro Basic)", 50L * 1024 * 1024),
    NITRO("500 MB (Nitro)", 500L * 1024 * 1024);

    companion object {
        val DEFAULT = ULTRA_SAFE
    }
}
