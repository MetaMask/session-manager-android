package com.web3auth.session_manager_android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.web3auth.session_manager_android.crypto.SessionCrypto
import com.web3auth.session_manager_android.interfaces.StorageManagerOptions
import com.google.gson.Gson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageManagerInstrumentedTest {

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
    fun createAuthorizeUpdateInvalidate_withMockServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseUrl = server.url("/").toString().trimEnd('/')
        val sessionId = StorageManager.generateRandomSessionKey()
        val payload = JSONObject()
            .put("publicAddress", "0xabc")
            .put("privateKey", "1")
            .toString()

        val manager = StorageManager(
            context,
            StorageManagerOptions(
                sessionServerBaseUrl = baseUrl,
                sessionTime = 86400,
                sessionId = sessionId,
                allowedOrigin = context.packageName,
                useLocalStorage = true
            )
        )

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val created = manager.createSession(payload, context).get()
        assertEquals(manager.sessionId, created)
        val createReq = server.takeRequest()
        assertEquals("POST", createReq.method)
        assertTrue(createReq.path!!.endsWith("/v2/store/set"))

        val encrypted = SessionCrypto.encrypt(manager.sessionId!!, payload)
        server.enqueue(
            MockResponse()
                .setBody("""{"success":true,"message":${Gson().toJson(encrypted)}}""")
                .setResponseCode(200)
        )
        // Local cache should hit without a second server call if we authorize immediately
        // after create (useLocalStorage=true stored the payload).
        val authorized = manager.authorizeSession(context.packageName, context).get()
        assertEquals(payload, authorized)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        manager.updateSession("""{"publicAddress":"0xdef"}""", context).get()
        val updateReq = server.takeRequest()
        assertEquals("PUT", updateReq.method)
        assertTrue(updateReq.path!!.endsWith("/v2/store/update"))

        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val invalidated = manager.invalidateSession(context).get()
        assertTrue(invalidated)
        val invalidateReq = server.takeRequest()
        assertEquals("POST", invalidateReq.method)
    }
}
