package com.huafeng.beaconzone

import java.util.UUID

data class Zone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uuid: String,
    val major: Int,
    val minor: Int
)
