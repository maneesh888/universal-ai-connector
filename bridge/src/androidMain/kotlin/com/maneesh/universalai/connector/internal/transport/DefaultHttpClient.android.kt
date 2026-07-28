package com.maneesh.universalai.connector.internal.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout

internal actual fun createDefaultHttpClient(): HttpClient =
    HttpClient(Android) {
        install(HttpTimeout)
    }
