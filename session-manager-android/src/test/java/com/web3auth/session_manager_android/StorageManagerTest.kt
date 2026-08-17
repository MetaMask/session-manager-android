package com.web3auth.session_manager_android

import com.web3auth.session_manager_android.handlers.LocalStorageHandler
import com.web3auth.session_manager_android.interfaces.StorageHandler
import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.interfaces.StorageHandlerStoreOptions
import com.web3auth.session_manager_android.interfaces.StorageOperation
import com.web3auth.session_manager_android.storage.MapStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

class FakeServerHandler : StorageHandler {
    val stored = mutableMapOf<String, String>()
    val operations = mutableListOf<StorageOperation>()

    override fun getStorageKey(key: String): String = "server:$key"

    override suspend fun storeData(key: String, data: String, options: StorageHandlerStoreOptions) {
        operations.add(options.operation)
        stored[key] = data
    }

    override suspend fun retrieveData(key: String, options: StorageHandlerRetrieveOptions): String? {
        return stored[key]
    }

    override fun clearStorage(key: String) {
        stored.remove(key)
    }

    override fun clearOrphanedData(baseKey: String) {
        stored.keys.filter { it.startsWith(baseKey) }.forEach { stored.remove(it) }
    }
}

class ThrowingStorage(
    private val throwOnSet: Boolean = false,
    private val throwOnGet: Boolean = false
) : com.web3auth.session_manager_android.storage.SimpleStorage {
    override fun getItem(key: String): String? {
        if (throwOnGet) throw RuntimeException("local retrieve failed")
        return null
    }

    override fun setItem(key: String, value: String) {
        if (throwOnSet) throw RuntimeException("local store failed")
    }

    override fun removeItem(key: String) {}

    override fun keys(): Set<String> = emptySet()
}

class StorageManagerTest {

    private fun sessionId(): String = StorageManager.generateRandomSessionKey()

    @Test
    fun setSessionId_validatesAndPads() {
        val manager = StorageManager.createForTest(
            sessionId = "ab",
            serverHandler = FakeServerHandler()
        )
        assertTrue(manager.sessionId!!.startsWith("0x"))
        assertEquals(66, manager.sessionId!!.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setSessionId_rejectsNonHex() {
        StorageManager.createForTest(serverHandler = FakeServerHandler()).setSessionId("not-hex")
    }

    @Test
    fun createSession_writesServerAndOptionalLocal() {
        val server = FakeServerHandler()
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            sessionNamespace = "ns",
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = local
        )
        val result = manager.createSession("""{"user":"1"}""", null).get()
        assertEquals(manager.sessionId, result)
        assertEquals(listOf(StorageOperation.CREATE), server.operations)
        assertEquals("""{"user":"1"}""", server.stored[manager.sessionId])
        assertEquals("""{"user":"1"}""", runBlocking { local.retrieveData(manager.sessionId!!) })
    }

    @Test
    fun authorizeSession_returnsLocalHitWithoutServer() {
        val server = FakeServerHandler()
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = local
        )
        runBlocking { local.storeData(manager.sessionId!!, """{"cached":true}""") }
        val result = manager.authorizeSession("origin", null).get()
        assertEquals("""{"cached":true}""", result)
        assertTrue(server.stored.isEmpty())
    }

    @Test
    fun authorizeSession_fallsBackToServerOnLocalMiss() {
        val server = FakeServerHandler()
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = local
        )
        server.stored[manager.sessionId!!] = """{"from":"server"}"""
        val result = manager.authorizeSession("origin", null).get()
        assertEquals("""{"from":"server"}""", result)
        assertEquals("""{"from":"server"}""", runBlocking { local.retrieveData(manager.sessionId!!) })
    }

    @Test
    fun authorizeSession_corruptedCacheFallsBackToServer() {
        val server = FakeServerHandler()
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = local
        )
        map.setItem(local.getStorageKey(manager.sessionId!!), "{{{{")
        server.stored[manager.sessionId!!] = """{"recovered":true}"""
        val result = manager.authorizeSession("origin", null).get()
        assertEquals("""{"recovered":true}""", result)
    }

    @Test
    fun updateSession_usesUpdateOperation() {
        val server = FakeServerHandler()
        val manager = StorageManager.createForTest(sessionId = sessionId(), serverHandler = server)
        manager.updateSession("""{"v":2}""", null).get()
        assertEquals(listOf(StorageOperation.UPDATE), server.operations)
        assertEquals("""{"v":2}""", server.stored[manager.sessionId])
    }

    @Test
    fun invalidateSession_clearsLocalAndSessionId() {
        val server = FakeServerHandler()
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = local
        )
        runBlocking { local.storeData(manager.sessionId!!, """{"x":1}""") }
        val ok = manager.invalidateSession(null).get()
        assertTrue(ok)
        assertEquals(listOf(StorageOperation.INVALIDATE), server.operations)
        assertNull(manager.sessionId)
        assertTrue(map.keys().isEmpty())
    }

    @Test
    fun clearOrphanedData_removesPrefixedKeys() {
        val map = MapStorage()
        val local = LocalStorageHandler(map, "ns:")
        val manager = StorageManager.createForTest(
            useLocalStorage = true,
            serverHandler = FakeServerHandler(),
            localStorageHandler = local
        )
        runBlocking {
            local.storeData("aaa1", """{"a":1}""")
            local.storeData("bbb1", """{"b":1}""")
        }
        manager.clearOrphanedData("aaa")
        assertNull(runBlocking { local.retrieveData("aaa1") })
        assertNotNull(runBlocking { local.retrieveData("bbb1") })
    }

    @Test
    fun safeLocalStorageOp_swallowsLocalErrors() {
        val server = FakeServerHandler()
        val throwing = LocalStorageHandler(ThrowingStorage(throwOnSet = true), "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = throwing
        )
        val result = manager.createSession("""{"ok":true}""", null).get()
        assertEquals(manager.sessionId, result)
        assertEquals("""{"ok":true}""", server.stored[manager.sessionId])
    }

    @Test
    fun safeLocalStorageOp_swallowsRetrieveErrorsAndHitsServer() {
        val server = FakeServerHandler()
        val throwing = LocalStorageHandler(ThrowingStorage(throwOnGet = true), "ns:")
        val manager = StorageManager.createForTest(
            sessionId = sessionId(),
            useLocalStorage = true,
            serverHandler = server,
            localStorageHandler = throwing
        )
        server.stored[manager.sessionId!!] = """{"server":true}"""
        val result = manager.authorizeSession("o", null).get()
        assertEquals("""{"server":true}""", result)
    }

    @Test
    fun checkSessionParams_throwsWhenMissing() {
        val manager = StorageManager.createForTest(serverHandler = FakeServerHandler())
        try {
            manager.createSession("{}", null).get()
            assertFalse("expected failure", true)
        } catch (e: ExecutionException) {
            assertTrue(e.cause?.message?.contains("Session id is required") == true)
        }
    }

    @Test
    fun generateRandomSessionKey_isPrefixedHex() {
        val key = StorageManager.generateRandomSessionKey()
        assertTrue(key.startsWith("0x"))
        assertEquals(66, key.length)
    }
}
