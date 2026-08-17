package com.web3auth.session_manager_android.crypto

import com.google.gson.GsonBuilder
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import com.web3auth.session_manager_android.types.AES256CBC
import com.web3auth.session_manager_android.types.Ecies
import com.web3auth.session_manager_android.util.HexUtils
import org.bouncycastle.util.encoders.Hex
import java.nio.charset.StandardCharsets

/**
 * Encrypt/decrypt/sign helpers shared by ServerHandler and AuthSessionManager.
 * Keeps the existing AES-256-CBC + ECIES + keccak256-ECDSA flow used by session server v2.
 */
internal object SessionCrypto {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun encrypt(sessionId: String, data: String): String {
        val priv = HexUtils.remove0x(sessionId)
        val ephemKey = KeyStoreManager.getUncompressedPubKey(sessionId)
        val ivKey = KeyStoreManager.randomBytes(16)
        val aes256cbc = AES256CBC()
        val aesKey = aes256cbc.getAESKey(priv, ephemKey)
        val macKey = aes256cbc.getMacKey(priv, ephemKey)
        val encryptedData = aes256cbc.encrypt(data.toByteArray(StandardCharsets.UTF_8), aesKey, ivKey)
        val mac = aes256cbc.getMac(encryptedData, macKey, ivKey, Hex.decode(ephemKey))
        val encryptedMetadata = Ecies(
            Hex.toHexString(ivKey),
            ephemKey,
            Hex.toHexString(encryptedData),
            Hex.toHexString(mac)
        )
        return gson.toJson(encryptedMetadata)
    }

    fun decrypt(sessionId: String, encryptedJson: String): String {
        val ecies: Ecies = gson.fromJson(encryptedJson, Ecies::class.java)
        val priv = HexUtils.remove0x(sessionId)
        val aes256cbc = AES256CBC()
        val aesKey = aes256cbc.getAESKey(priv, ecies.ephemPublicKey)
        val macKey = aes256cbc.getMacKey(priv, ecies.ephemPublicKey)
        val share = aes256cbc.decrypt(
            ecies.ciphertext,
            aesKey,
            macKey,
            ecies.mac,
            Hex.decode(ecies.iv),
            Hex.decode(ecies.ephemPublicKey)
        )
        return String(share, Charsets.UTF_8)
    }

    fun sign(sessionId: String, data: String): String {
        return KeyStoreManager.getECDSASignature(HexUtils.toBigInteger(sessionId), data)
    }
}
