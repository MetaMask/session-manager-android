package com.web3auth.session_manager_android

import android.content.Context
import com.web3auth.session_manager_android.api.ApiHelper
import com.web3auth.session_manager_android.handlers.LocalStorageHandler
import com.web3auth.session_manager_android.handlers.ServerHandler
import com.web3auth.session_manager_android.interfaces.DEFAULT_LOCAL_STORAGE_NAMESPACE
import com.web3auth.session_manager_android.interfaces.DEFAULT_SESSION_TIMEOUT
import com.web3auth.session_manager_android.interfaces.StorageHandler
import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.interfaces.StorageHandlerStoreOptions
import com.web3auth.session_manager_android.interfaces.StorageManagerOptions
import com.web3auth.session_manager_android.interfaces.StorageOperation
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import com.web3auth.session_manager_android.storage.EncryptedPrefsStorage
import com.web3auth.session_manager_android.types.ErrorCode
import com.web3auth.session_manager_android.types.SessionManagerError
import com.web3auth.session_manager_android.util.HexUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

open class StorageManager private constructor(
    private val context: Context?,
    val sessionServerBaseUrl: String,
    val sessionNamespace: String?,
    val allowedOrigin: String,
    val sessionTime: Int,
    private val useLocalStorage: Boolean,
    private val serverHandler: StorageHandler,
    private var localStorageHandler: LocalStorageHandler?,
    private val skipNetworkCheck: Boolean = false
) : BaseStorageManager() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    constructor(
        context: Context,
        sessionServerBaseUrl: String,
        sessionTime: Int = DEFAULT_SESSION_TIMEOUT,
        allowedOrigin: String = "*",
        sessionId: String? = null,
        sessionNamespace: String? = null,
        useLocalStorage: Boolean = false
    ) : this(
        context = context,
        options = StorageManagerOptions(
            sessionServerBaseUrl = sessionServerBaseUrl,
            sessionNamespace = sessionNamespace,
            sessionTime = sessionTime,
            sessionId = sessionId,
            allowedOrigin = allowedOrigin,
            useLocalStorage = useLocalStorage
        )
    )

    constructor(
        context: Context,
        options: StorageManagerOptions
    ) : this(
        context = context.applicationContext,
        sessionServerBaseUrl = options.sessionServerBaseUrl,
        sessionNamespace = options.sessionNamespace?.takeIf { it.isNotEmpty() },
        allowedOrigin = options.allowedOrigin?.takeIf { it.isNotEmpty() } ?: "*",
        sessionTime = options.sessionTime?.takeIf { it > 0 } ?: DEFAULT_SESSION_TIMEOUT,
        useLocalStorage = options.useLocalStorage ?: false,
        serverHandler = ServerHandler(
            options.sessionServerBaseUrl,
            ApiHelper.getWeb3AuthApi(options.sessionServerBaseUrl)
        ),
        localStorageHandler = null
    ) {
        KeyStoreManager.initializePreferences(context.applicationContext)
        initiateKeyStoreManager()
        options.sessionId?.let { setSessionId(it) }
    }

    init {
        require(sessionServerBaseUrl.isNotBlank()) {
            "sessionServerBaseUrl is required"
        }
    }

    val localStorageHandlerStorageKey: String?
        get() {
            val handler = getLocalStorageHandler() ?: return null
            val id = sessionId ?: return null
            return handler.getStorageKey(id)
        }

    val serverHandlerStorageKey: String?
        get() {
            val id = sessionId ?: return null
            return serverHandler.getStorageKey(id)
        }

    private val baseLocalStorageKey: String
        get() = "${sessionNamespace ?: DEFAULT_LOCAL_STORAGE_NAMESPACE}:"

    private fun initiateKeyStoreManager() {
        try {
            KeyStoreManager.getKeyGenerator()
        } catch (_: Exception) {
            // Key may already exist in AndroidKeyStore.
        }
    }

    override fun setSessionId(sessionId: String) {
        require(HexUtils.isHexString(sessionId)) { "Session id must be a hex string" }
        _sessionId = HexUtils.add0x(HexUtils.padHexString(sessionId))
    }

    /**
     * Creates a new encrypted session on the session server.
     *
     * @param data Session payload as a JSON string.
     * @param context Used for connectivity checks. Defaults to the constructor context.
     */
    override fun createSession(
        data: String,
        context: Context?
    ): CompletableFuture<String> {
        return future {
            checkSessionParams()
            ensureNetwork(context)
            val id = sessionId!!
            serverHandler.storeData(
                id,
                data,
                StorageHandlerStoreOptions(
                    operation = StorageOperation.CREATE,
                    namespace = sessionNamespace,
                    timeout = sessionTime,
                    allowedOrigin = allowedOrigin
                )
            )
            safeLocalStorageOp { handler -> handler.storeData(id, data) }
            id
        }
    }

    /**
     * Authorizes the current session. Checks the local cache first when
     * [useLocalStorage] is enabled, then falls back to the session server.
     */
    override fun authorizeSession(
        origin: String,
        context: Context?
    ): CompletableFuture<String> {
        return future {
            checkSessionParams()
            val id = sessionId!!
            val localData = safeLocalStorageOp { handler -> handler.retrieveData(id) }
            if (localData != null) {
                return@future localData
            }
            ensureNetwork(context)
            val response = serverHandler.retrieveData(
                id,
                StorageHandlerRetrieveOptions(
                    namespace = sessionNamespace,
                    origin = origin
                )
            ) ?: throw Exception("Session Expired or Invalid public key")
            safeLocalStorageOp { handler -> handler.storeData(id, response) }
            response
        }
    }

    /**
     * Updates an existing session via PUT /v2/store/update.
     */
    override fun updateSession(
        data: String,
        context: Context?
    ): CompletableFuture<Unit> {
        return future {
            checkSessionParams()
            ensureNetwork(context)
            val id = sessionId!!
            serverHandler.storeData(
                id,
                data,
                StorageHandlerStoreOptions(
                    operation = StorageOperation.UPDATE,
                    namespace = sessionNamespace,
                    allowedOrigin = allowedOrigin
                )
            )
            safeLocalStorageOp { handler -> handler.storeData(id, data) }
        }
    }

    /**
     * Invalidates the current session (timeout: 1) and clears local cache.
     */
    override fun invalidateSession(context: Context?): CompletableFuture<Boolean> {
        return future {
            checkSessionParams()
            ensureNetwork(context)
            val id = sessionId!!
            serverHandler.storeData(
                id,
                "{}",
                StorageHandlerStoreOptions(
                    operation = StorageOperation.INVALIDATE,
                    namespace = sessionNamespace
                )
            )
            clearStorage()
            _sessionId = null
            true
        }
    }

    fun clearStorage() {
        checkSessionParams()
        val handler = getLocalStorageHandler() ?: return
        handler.clearStorage(sessionId!!)
    }

    fun clearOrphanedData(baseKey: String = "") {
        val handler = getLocalStorageHandler() ?: return
        handler.clearOrphanedData(baseKey)
    }

    /**
     * Runs a local-storage operation, swallowing any errors so that failures
     * never break the primary server-backed flow.
     */
    private suspend fun <R> safeLocalStorageOp(fn: suspend (LocalStorageHandler) -> R): R? {
        return try {
            val handler = getLocalStorageHandler() ?: return null
            fn(handler)
        } catch (_: Exception) {
            null
        }
    }

    private fun getLocalStorageHandler(): LocalStorageHandler? {
        if (!useLocalStorage) return null
        localStorageHandler?.let { return it }
        val appContext = context ?: return null
        localStorageHandler = LocalStorageHandler(
            EncryptedPrefsStorage(appContext, LOCAL_PREFS_NAME),
            baseLocalStorageKey
        )
        return localStorageHandler
    }

    private fun ensureNetwork(context: Context?) {
        if (skipNetworkCheck) return
        if (!ApiHelper.isNetworkAvailable(context)) {
            throw Exception(SessionManagerError.getError(ErrorCode.RUNTIME_ERROR))
        }
    }

    private fun <T> future(block: suspend () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        scope.launch {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result
    }

    companion object {
        private const val LOCAL_PREFS_NAME = "w3a_session_manager_local"

        @JvmField
        val SESSION_SERVER_API_URL = com.web3auth.session_manager_android.interfaces.SESSION_SERVER_API_URL

        fun generateRandomSessionKey(): String {
            return KeyStoreManager.generateRandomSessionKey()
        }

        fun getSessionIdFromStorage(): String {
            return KeyStoreManager.getPreferencesData(KeyStoreManager.SESSION_ID_TAG).toString()
        }

        fun deleteSessionIdFromStorage() {
            KeyStoreManager.deletePreferencesData(KeyStoreManager.SESSION_ID_TAG)
        }

        fun saveSessionIdToStorage(sessionId: String) {
            if (sessionId.isNotEmpty() && sessionId.isNotBlank()) {
                KeyStoreManager.savePreferenceData(KeyStoreManager.SESSION_ID_TAG, sessionId)
            }
        }

        /**
         * Test-only constructor that injects handlers and skips Android KeyStore init.
         */
        internal fun createForTest(
            sessionServerBaseUrl: String = SESSION_SERVER_API_URL,
            sessionNamespace: String? = null,
            allowedOrigin: String = "*",
            sessionTime: Int = DEFAULT_SESSION_TIMEOUT,
            useLocalStorage: Boolean = false,
            sessionId: String? = null,
            serverHandler: StorageHandler,
            localStorageHandler: LocalStorageHandler? = null,
            context: Context? = null
        ): StorageManager {
            val manager = StorageManager(
                context = context,
                sessionServerBaseUrl = sessionServerBaseUrl,
                sessionNamespace = sessionNamespace,
                allowedOrigin = allowedOrigin,
                sessionTime = sessionTime,
                useLocalStorage = useLocalStorage,
                serverHandler = serverHandler,
                localStorageHandler = localStorageHandler,
                skipNetworkCheck = true
            )
            sessionId?.let { manager.setSessionId(it) }
            return manager
        }
    }
}
