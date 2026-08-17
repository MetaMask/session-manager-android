package com.web3auth.session_manager_android.auth.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedSharedPreferencesAdapterTest {

    @Test
    fun readWriteRemove() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = EncryptedSharedPreferencesAdapter(
            context,
            "w3a_adapter_test_${System.currentTimeMillis()}"
        )
        assertNull(adapter.get("k"))
        adapter.set("k", "secret")
        assertEquals("secret", adapter.get("k"))
        adapter.remove("k")
        assertNull(adapter.get("k"))
    }
}
