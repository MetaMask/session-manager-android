package com.web3auth.session_manager_android.auth.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryStorageAdapterTest {
    @Test
    fun getSetRemove() = runBlocking {
        val storage = MemoryStorageAdapter()
        assertNull(storage.get("k"))
        storage.set("k", "v")
        assertEquals("v", storage.get("k"))
        storage.remove("k")
        assertNull(storage.get("k"))
    }
}
