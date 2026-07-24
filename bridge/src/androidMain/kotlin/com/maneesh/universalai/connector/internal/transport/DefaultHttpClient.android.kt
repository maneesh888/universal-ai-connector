package com.maneesh.universalai.connector.internal.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android

internal actual fun createDefaultHttpClient(): HttpClient = HttpClient(Android)
