package com.web3auth.session_manager_android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.web3auth.session_manager_android.interfaces.SESSION_SERVER_API_URL
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import com.web3auth.session_manager_android.types.AES256CBC
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException

@RunWith(AndroidJUnit4::class)
class SessionManagerTest {

    private lateinit var storageManager: StorageManager

    @Test
    @Throws(ExecutionException::class, InterruptedException::class)
    fun test_createSession() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sessionId = StorageManager.generateRandomSessionKey()
        storageManager = StorageManager(
            context,
            SESSION_SERVER_API_URL,
            86400,
            context.packageName,
            sessionId
        )
        val json = JSONObject()
        json.put(
            "privateKey",
            "91714924788458331086143283967892938475657483928374623640418082526960471979197446884"
        )
        json.put("publicAddress", "0x93475c78dv0jt80f2b6715a5c53838eC4aC96EF7")
        val sessionKey = storageManager.createSession(
            json.toString(),
            context
        ).get()
        assert(sessionKey.isNotEmpty())
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class)
    fun test_authorizeSession() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sessionId = StorageManager.generateRandomSessionKey()
        storageManager = StorageManager(
            context,
            SESSION_SERVER_API_URL,
            86400,
            context.packageName,
            sessionId,
            "sfa"
        )
        val json = JSONObject()
        json.put(
            "privateKey",
            "91714924788458331086143283967892938475657483928374623640418082526960471979197446884"
        )
        json.put("publicAddress", "0x93475c78dv0jt80f2b6715a5c53838eC4aC96EF7")
        val created = storageManager.createSession(
            json.toString(),
            context
        ).get()
        StorageManager.saveSessionIdToStorage(created)
        assertTrue(created.isNotEmpty())
        val authResponse = storageManager.authorizeSession(
            context.packageName,
            context
        ).get()
        val resp = JSONObject(authResponse)
        assert(resp.get("privateKey").toString().isNotEmpty())
        assert(resp.get("publicAddress").toString().isNotEmpty())
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class)
    fun test_invalidateSession() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sessionId = StorageManager.generateRandomSessionKey()
        storageManager = StorageManager(
            context,
            SESSION_SERVER_API_URL,
            86400,
            context.packageName,
            sessionId
        )
        val json = JSONObject()
        json.put(
            "privateKey",
            "91714924788458331086143283967892938475657483928374623640418082526960471979197446884"
        )
        json.put("publicAddress", "0x93475c78dv0jt80f2b6715a5c53838eC4aC96EF7")
        val created = storageManager.createSession(
            json.toString(),
            context
        ).get()
        StorageManager.saveSessionIdToStorage(created)
        val invalidateRes = storageManager.invalidateSession(context).get()
        assertEquals(invalidateRes, true)
        StorageManager.deleteSessionIdFromStorage()
        val res = StorageManager.getSessionIdFromStorage().isNotEmpty()
        assertEquals(res, false)
    }

    @Test
    @Throws(ExecutionException::class, InterruptedException::class)
    fun test_updateSession() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val sessionId = StorageManager.generateRandomSessionKey()
        storageManager = StorageManager(
            context,
            SESSION_SERVER_API_URL,
            86400,
            context.packageName,
            sessionId
        )
        val createdPayload = JSONObject()
            .put("publicAddress", "0x93475c78dv0jt80f2b6715a5c53838eC4aC96EF7")
            .put("privateKey", "1")
        storageManager.createSession(createdPayload.toString(), context).get()

        val updatedPayload = JSONObject()
            .put("publicAddress", "0x1111111111111111111111111111111111111111")
            .put("privateKey", "2")
            .put("updated", true)
        storageManager.updateSession(updatedPayload.toString(), context).get()

        val authResponse = storageManager.authorizeSession(context.packageName, context).get()
        val resp = JSONObject(authResponse)
        assertEquals("0x1111111111111111111111111111111111111111", resp.getString("publicAddress"))
        assertEquals("2", resp.getString("privateKey"))
        assertEquals(true, resp.getBoolean("updated"))
    }

    @Test
    @Throws(ExecutionException::class)
    fun testAes() {
        val message = "Hello World"
        val sessionId = KeyStoreManager.generateRandomSessionKey()
        val ephemKey = KeyStoreManager.getUncompressedPubKey(sessionId)
        val aes256cbc = AES256CBC()
        val priv = com.web3auth.session_manager_android.util.HexUtils.remove0x(sessionId)
        val aesKey = aes256cbc.getAESKey(priv, ephemKey)
        val iv = KeyStoreManager.randomBytes(16)
        val macKey = aes256cbc.getMacKey(priv, ephemKey)
        assert(!aesKey.contentEquals(macKey))
        assert(aesKey.size == 32)
        assert(macKey.size == 32)
        val encrypted = aes256cbc.encrypt(message.toByteArray(Charsets.UTF_8), aesKey, iv)
        val mac = aes256cbc.getMac(encrypted, macKey, iv, Hex.decode(ephemKey))
        val decrypted = aes256cbc.decrypt(
            Hex.toHexString(encrypted),
            aesKey,
            macKey,
            Hex.toHexString(mac),
            iv,
            Hex.decode(ephemKey)
        )
        val decryptedString = String(decrypted, Charsets.UTF_8)
        assert(decryptedString == message)
    }
}
