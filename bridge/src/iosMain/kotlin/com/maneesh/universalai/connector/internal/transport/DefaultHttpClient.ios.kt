package com.maneesh.universalai.connector.internal.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout

internal actual fun createDefaultHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(HttpTimeout)
    }
