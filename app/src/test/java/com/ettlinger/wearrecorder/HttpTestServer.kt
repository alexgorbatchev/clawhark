package com.ettlinger.wearrecorder

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** Uses MockWebServer's real HTTP transport to exercise production connections. */
class HttpTestServer : Closeable {
    private val server = MockWebServer().apply { start() }
    val baseUrl: String get() = server.url("/").toString().removeSuffix("/")
    val requestCount: Int get() = server.requestCount
    fun enqueue(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).apply {
            headers.forEach { (name, value) -> setHeader(name, value) }
        })
    }
    fun takeRequest(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    fun holdResponse(body: String): CountDownLatch {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release HTTP response" }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        return release
    }
    override fun close() = server.shutdown()
}
