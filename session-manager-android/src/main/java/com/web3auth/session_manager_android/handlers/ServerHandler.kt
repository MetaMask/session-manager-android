package com.web3auth.session_manager_android.handlers

import com.web3auth.session_manager_android.api.Web3AuthApi
import com.web3auth.session_manager_android.crypto.SessionCrypto
import com.web3auth.session_manager_android.interfaces.StorageHandler
import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.interfaces.StorageHandlerStoreOptions
import com.web3auth.session_manager_android.interfaces.StorageOperation
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import com.web3auth.session_manager_android.models.AuthorizeSessionRequest
import com.web3auth.session_manager_android.models.SessionRequestBody
import com.web3auth.session_manager_android.types.ErrorCode
import com.web3auth.session_manager_android.types.SessionManagerError

class ServerHandler(
    private val sessionServerBaseUrl: String,
    private val api: Web3AuthApi
) : StorageHandler {

    override fun getStorageKey(key: String): String {
        return KeyStoreManager.getUncompressedPubKey(key)
    }

    override suspend fun storeData(
        key: String,
        data: String,
        options: StorageHandlerStoreOptions
    ) {
        val pubKey = getStorageKey(key)
        val encData = SessionCrypto.encrypt(key, data)
        val signature = SessionCrypto.sign(key, encData)
        val operation = options.operation
        val timeout = when (operation) {
            StorageOperation.CREATE -> options.timeout
            StorageOperation.INVALIDATE -> 1
            StorageOperation.UPDATE -> null
        }
        val body = SessionRequestBody(
            key = pubKey,
            data = encData,
            signature = signature,
            timeout = timeout,
            allowedOrigin = options.allowedOrigin,
            namespace = options.namespace
        )
        val headers = options.headers
        val response = when (operation) {
            StorageOperation.UPDATE -> api.updateSession(headers, body)
            StorageOperation.CREATE -> api.createSession(headers, body)
            StorageOperation.INVALIDATE -> api.invalidateSession(headers, body)
        }
        if (!response.isSuccessful) {
            throw Exception(SessionManagerError.getError(ErrorCode.SOMETHING_WENT_WRONG))
        }
    }

    override suspend fun retrieveData(
        key: String,
        options: StorageHandlerRetrieveOptions
    ): String? {
        val pubKey = getStorageKey(key)
        val response = api.authorizeSession(
            origin = options.origin,
            headers = options.headers,
            authorizeSessionRequest = AuthorizeSessionRequest(
                key = pubKey,
                namespace = options.namespace
            )
        )
        if (!response.isSuccessful) {
            throw Exception(SessionManagerError.getError(ErrorCode.SOMETHING_WENT_WRONG))
        }
        val body = response.body()
        if (body?.success == false && body.message.isNullOrEmpty()) {
            throw Exception(SessionManagerError.getError(ErrorCode.NOUSERFOUND))
        }
        val message = body?.message
        if (message.isNullOrEmpty()) {
            throw Exception("Session Expired or Invalid public key")
        }
        return try {
            SessionCrypto.decrypt(key, message)
        } catch (e: Exception) {
            throw Exception("There was an error decrypting data.", e)
        }
    }

    override fun clearStorage(key: String) {
        // No-op. Server entries expire based on timeout.
    }

    override fun clearOrphanedData(baseKey: String) {
        // No-op. Server entries expire based on timeout.
    }

    fun sessionServerBaseUrl(): String = sessionServerBaseUrl
}
