package com.web3auth.session_manager_android.handlers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.web3auth.session_manager_android.api.ApiHelper
import com.web3auth.session_manager_android.crypto.SessionCrypto
import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.interfaces.StorageHandlerStoreOptions
import com.web3auth.session_manager_android.interfaces.StorageOperation
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerHandlerTest {

    private lateinit var server: MockWebServer
    private lateinit var handler: ServerHandler
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        handler = ServerHandler(baseUrl, ApiHelper.getWeb3AuthApi(baseUrl))
        sessionId = KeyStoreManager.generateRandomSessionKey()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun storeData_create_postsEncryptedSignedPayload() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        handler.storeData(
            sessionId,
            """{"user":"1"}""",
            StorageHandlerStoreOptions(
                operation = StorageOperation.CREATE,
                namespace = "sfa",
                timeout = 86400,
                allowedOrigin = "*"
            )
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/v2/store/set"))
        val body = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
        assertTrue(body.get("key").asString.startsWith("04"))
        assertEquals(130, body.get("key").asString.length)
        assertTrue(body.get("data").asString.isNotEmpty())
        assertTrue(body.get("signature").asString.isNotEmpty())
        assertEquals(86400, body.get("timeout").asInt)
        assertEquals("*", body.get("allowedOrigin").asString)
        assertEquals("sfa", body.get("namespace").asString)
        val decrypted = SessionCrypto.decrypt(sessionId, body.get("data").asString)
        assertEquals("""{"user":"1"}""", decrypted)
    }

    @Test
    fun storeData_update_usesPutWithoutTimeout() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        handler.storeData(
            sessionId,
            """{"user":"2"}""",
            StorageHandlerStoreOptions(operation = StorageOperation.UPDATE)
        )
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.path!!.endsWith("/v2/store/update"))
        val body = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
        assertFalse(body.has("timeout") && !body.get("timeout").isJsonNull)
    }

    @Test
    fun storeData_invalidate_postsTimeoutOne() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        handler.storeData(
            sessionId,
            "{}",
            StorageHandlerStoreOptions(operation = StorageOperation.INVALIDATE)
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = Gson().fromJson(request.body.readUtf8(), JsonObject::class.java)
        assertEquals(1, body.get("timeout").asInt)
    }

    @Test
    fun retrieveData_decryptsServerMessage() = runBlocking {
        val payload = """{"hello":"world"}"""
        val encrypted = SessionCrypto.encrypt(sessionId, payload)
        server.enqueue(
            MockResponse()
                .setBody("""{"success":true,"message":${Gson().toJson(encrypted)}}""")
                .setResponseCode(200)
        )
        val result = handler.retrieveData(
            sessionId,
            StorageHandlerRetrieveOptions(namespace = "sfa", origin = "app")
        )
        assertEquals(payload, result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/v2/store/get"))
        assertEquals("app", request.getHeader("origin"))
    }

    @Test
    fun getStorageKey_isUncompressedPubKey() {
        val key = handler.getStorageKey(sessionId)
        assertTrue(key.startsWith("04"))
        assertEquals(130, key.length)
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
