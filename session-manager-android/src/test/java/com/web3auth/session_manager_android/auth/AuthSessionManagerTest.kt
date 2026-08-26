package com.web3auth.session_manager_android.auth

import com.web3auth.session_manager_android.auth.storage.MemoryStorageAdapter
import com.web3auth.session_manager_android.crypto.SessionCrypto
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AuthSessionManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var memory: MemoryStorageAdapter
    private lateinit var sessionId: String
    private lateinit var encryptedSession: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        memory = MemoryStorageAdapter()
        sessionId = KeyStoreManager.generateRandomSessionKey()
        encryptedSession = SessionCrypto.encrypt(sessionId, """{"user":"alice"}""")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager(): AuthSessionManager {
        val storage = StorageConfig(
            sessionId = memory,
            accessToken = memory,
            refreshToken = memory,
            idToken = memory
        )
        return AuthSessionManager(
            apiClientConfig = ApiClientConfig(baseURL = server.url("/").toString().trimEnd('/')),
            storage = storage
        )
    }

    @Test
    fun setTokens_roundTrip() = runBlocking {
        val session = manager()
        session.setTokens(
            AuthTokens(
                sessionId = sessionId,
                accessToken = "access",
                refreshToken = "refresh",
                idToken = "id"
            )
        )
        assertEquals("access", session.getAccessToken())
        assertEquals("refresh", session.getRefreshToken())
        assertEquals("id", session.getIdToken())
        assertTrue(session.getSessionId()!!.startsWith("0x"))
        assertEquals("access", memory.get("w3a:${STORAGE_KEYS.ACCESS_TOKEN}"))
        assertEquals("refresh", memory.get("w3a:${STORAGE_KEYS.REFRESH_TOKEN}"))
    }

    @Test
    fun authorize_refreshesAndDecrypts() = runBlocking {
        enqueueRefreshSuccess()
        val session = manager()
        session.setTokens(AuthTokens(sessionId = sessionId, accessToken = "old", refreshToken = "r1"))
        val data = session.authorize()
        assertEquals("""{"user":"alice"}""", data)
        assertTrue(session.isAuthenticated())
        assertEquals("new-access", session.getAccessToken())
        assertEquals("new-refresh", session.getRefreshToken())
    }

    @Test
    fun logout_clearsEvenOnError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("fail"))
        val session = manager()
        session.setTokens(AuthTokens(sessionId = sessionId, accessToken = "a", refreshToken = "r"))
        session.logout()
        assertNull(session.getAccessToken())
        assertNull(session.getRefreshToken())
        assertNull(session.getSessionId())
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun refresh_isDeduplicated() = runBlocking {
        val refreshCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/v1/auth/session")) {
                    refreshCount.incrementAndGet()
                    Thread.sleep(150)
                    return refreshResponse()
                }
                return MockResponse().setResponseCode(404)
            }
        }
        val session = manager()
        session.setTokens(AuthTokens(sessionId = sessionId, accessToken = "old", refreshToken = "r1"))
        val results = listOf(
            async { session.ensureRefresh(false) },
            async { session.ensureRefresh(false) },
            async { session.ensureRefresh(false) }
        ).awaitAll()
        assertEquals(1, refreshCount.get())
        assertEquals("new-access", results[0].access_token)
        assertEquals("new-access", results[1].access_token)
    }

    @Test
    fun skipIfFresh_syncsFromStorageWithoutApi() = runBlocking {
        val session = manager()
        session.setTokens(AuthTokens(sessionId = sessionId, accessToken = "in-memory", refreshToken = "r1"))
        memory.set("w3a:${STORAGE_KEYS.ACCESS_TOKEN}", "from-storage")
        val response = session.ensureRefresh(skipIfFresh = true)
        assertEquals("from-storage", response.access_token)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun authorize_clearsOnFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val session = manager()
        session.setTokens(AuthTokens(sessionId = sessionId, accessToken = "old", refreshToken = "r1"))
        val data = session.authorize()
        assertNull(data)
        assertNull(session.getAccessToken())
    }

    private fun enqueueRefreshSuccess() {
        server.enqueue(refreshResponse())
    }

    private fun refreshResponse(): MockResponse {
        val escaped = encryptedSession.replace("\\", "\\\\").replace("\"", "\\\"")
        return MockResponse()
            .setResponseCode(200)
            .setBody(
                """{"access_token":"new-access","refresh_token":"new-refresh","session_data":"$escaped"}"""
            )
    }
}
