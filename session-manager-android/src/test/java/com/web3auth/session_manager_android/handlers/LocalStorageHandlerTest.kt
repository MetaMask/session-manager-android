package com.web3auth.session_manager_android.handlers

import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.storage.MapStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalStorageHandlerTest {

    @Test
    fun storeAndRetrieve_roundTrip() = runBlocking {
        val storage = MapStorage()
        val handler = LocalStorageHandler(storage, "ns:")
        handler.storeData("0xabc", """{"user":"1"}""")
        assertEquals("""{"user":"1"}""", handler.retrieveData("0xabc"))
        assertEquals("ns:abc", handler.getStorageKey("0xabc"))
    }

    @Test
    fun retrieve_corruptedCache_removesKeyAndReturnsNull() = runBlocking {
        val storage = MapStorage()
        val handler = LocalStorageHandler(storage, "ns:")
        storage.setItem("ns:abc", "not-json")
        assertNull(handler.retrieveData("abc"))
        assertNull(storage.getItem("ns:abc"))
    }

    @Test
    fun clearStorage_removesOneEntry() = runBlocking {
        val storage = MapStorage()
        val handler = LocalStorageHandler(storage, "ns:")
        handler.storeData("aaa", """{"a":1}""")
        handler.storeData("bbb", """{"b":1}""")
        handler.clearStorage("aaa")
        assertNull(handler.retrieveData("aaa"))
        assertEquals("""{"b":1}""", handler.retrieveData("bbb"))
    }

    @Test
    fun clearOrphanedData_removesMatchingPrefix() = runBlocking {
        val storage = MapStorage()
        val handler = LocalStorageHandler(storage, "ns:")
        handler.storeData("aaa111", """{"a":1}""")
        handler.storeData("aaa222", """{"a":2}""")
        handler.storeData("bbb111", """{"b":1}""")
        handler.clearOrphanedData("aaa")
        assertNull(handler.retrieveData("aaa111"))
        assertNull(handler.retrieveData("aaa222"))
        assertEquals("""{"b":1}""", handler.retrieveData("bbb111"))
    }

    @Test
    fun retrieve_missingKey_returnsNull() = runBlocking {
        val handler = LocalStorageHandler(MapStorage(), "ns:")
        assertNull(handler.retrieveData("missing", StorageHandlerRetrieveOptions()))
    }
}
