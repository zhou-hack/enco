package xyz.her3.enco.core.protocol

import kotlinx.serialization.json.Json

val encoJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
