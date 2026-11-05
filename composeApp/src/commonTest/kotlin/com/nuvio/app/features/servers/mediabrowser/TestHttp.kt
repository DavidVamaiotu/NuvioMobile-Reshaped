package com.nuvio.app.features.servers.mediabrowser

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

internal class TestHttp(private val responder: (HttpRequestData) -> String = { error("Unexpected request ${it.url}") }) {
    val requests = mutableListOf<HttpRequestData>()
    private val requestMutex = Mutex()

    val client = HttpClient(
        MockEngine { request ->
            // MockEngine handlers can run concurrently for multi-library searches.
            requestMutex.withLock { requests += request }
            respond(
                content = responder(request),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
}

internal val HttpRequestData.text: String
    get() = (body as TextContent).text
