package com.web3auth.session_manager_android.auth

import kotlinx.coroutines.delay
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FakeAuthProvider(
    @Volatile var token: String? = "token-1",
    private val onUnauthorized: () -> String = { "token-2" }
) : IHttpSessionAuthProvider {
    val unauthorizedCalls = AtomicInteger(0)

    override suspend fun getAccessToken(): String? = token

    override suspend fun handleUnauthorized(): String {
        unauthorizedCalls.incrementAndGet()
        delay(80)
        val next = onUnauthorized()
        token = next
        return next
    }
}

class HttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun authenticated_attachesBearerAndRetriesOnceOn401() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val provider = FakeAuthProvider()
        val client = HttpClient(provider)
        val result = client.get(server.url("/resource").toString(), HttpClientRequestOptions(authenticated = true)).get()
        assertEquals("""{"ok":true}""", result)
        assertEquals(1, provider.unauthorizedCalls.get())
        val first = server.takeRequest()
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        val second = server.takeRequest()
        assertEquals("Bearer token-2", second.getHeader("Authorization"))
    }

    @Test
    fun concurrent401_singleRefresh() {
        val hits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = hits.incrementAndGet()
                return if (request.getHeader("Authorization") == "Bearer token-1") {
                    MockResponse().setResponseCode(401).setBody("expired")
                } else {
                    MockResponse().setResponseCode(200).setBody("""{"n":$n}""")
                }
            }
        }
        val provider = FakeAuthProvider()
        val client = HttpClient(provider)
        val pool = Executors.newFixedThreadPool(4)
        val futures = (1..4).map {
            java.util.concurrent.CompletableFuture.supplyAsync(
                {
                    client.get(server.url("/item").toString(), HttpClientRequestOptions(authenticated = true)).get()
                },
                pool
            )
        }
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, provider.unauthorizedCalls.get())
    }

    @Test
    fun unauthenticated_doesNotAttachTokenOrRetry() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val provider = FakeAuthProvider()
        val client = HttpClient(provider)
        try {
            client.get(server.url("/public").toString(), HttpClientRequestOptions(authenticated = false)).get()
            org.junit.Assert.fail("expected 401")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("401") || e.cause?.message?.contains("401") == true)
        }
        assertEquals(0, provider.unauthorizedCalls.get())
        val request = server.takeRequest()
        assertEquals(null, request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun postPutPatchDelete_succeed() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        val client = HttpClient(FakeAuthProvider())
        val url = server.url("/x").toString()
        assertEquals("ok", client.post(url, mapOf("a" to 1)).get())
        assertEquals("ok", client.put(url, mapOf("a" to 2)).get())
        assertEquals("ok", client.patch(url, mapOf("a" to 3)).get())
        assertEquals("ok", client.delete(url).get())
        assertEquals("POST", server.takeRequest().method)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
    }
}
