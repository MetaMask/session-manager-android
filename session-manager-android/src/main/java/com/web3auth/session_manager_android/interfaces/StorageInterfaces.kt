package com.web3auth.session_manager_android.interfaces

import android.content.Context
import java.util.concurrent.CompletableFuture

const val SESSION_SERVER_API_URL = "https://api.web3auth.io/session-service"
const val DEFAULT_SESSION_TIMEOUT = 86400
const val DEFAULT_LOCAL_STORAGE_NAMESPACE = "w3a_session_manager_default"

interface IStorageManager {
    fun createSession(data: String, context: Context?): CompletableFuture<String>
    fun authorizeSession(origin: String, context: Context?): CompletableFuture<String>
    fun updateSession(data: String, context: Context?): CompletableFuture<Unit>
    fun invalidateSession(context: Context?): CompletableFuture<Boolean>
}

enum class StorageOperation {
    CREATE,
    UPDATE,
    INVALIDATE
}

data class StorageHandlerStoreOptions(
    val headers: Map<String, String> = emptyMap(),
    val namespace: String? = null,
    val timeout: Int? = null,
    val allowedOrigin: String? = null,
    val operation: StorageOperation = StorageOperation.CREATE
)

data class StorageHandlerRetrieveOptions(
    val headers: Map<String, String> = emptyMap(),
    val namespace: String? = null,
    val origin: String? = null
)

interface StorageHandler {
    fun getStorageKey(key: String): String
    suspend fun storeData(
        key: String,
        data: String,
        options: StorageHandlerStoreOptions = StorageHandlerStoreOptions()
    )
    suspend fun retrieveData(
        key: String,
        options: StorageHandlerRetrieveOptions = StorageHandlerRetrieveOptions()
    ): String?
    fun clearStorage(key: String)
    fun clearOrphanedData(baseKey: String = "")
}

data class StorageManagerOptions(
    val sessionServerBaseUrl: String,
    val sessionNamespace: String? = null,
    val sessionTime: Int? = null,
    val sessionId: String? = null,
    val allowedOrigin: String? = null,
    val useLocalStorage: Boolean? = null
)

data class ApiRequestParams(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val data: Any? = null
)
