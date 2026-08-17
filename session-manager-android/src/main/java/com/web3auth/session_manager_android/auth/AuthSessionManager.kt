package com.web3auth.session_manager_android.auth

import android.content.Context
import com.google.gson.GsonBuilder
import com.web3auth.session_manager_android.auth.storage.EncryptedSharedPreferencesAdapter
import com.web3auth.session_manager_android.auth.storage.IStorageAdapter
import com.web3auth.session_manager_android.auth.storage.MemoryStorageAdapter
import com.web3auth.session_manager_android.crypto.SessionCrypto
import com.web3auth.session_manager_android.util.HexUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private data class ResolvedStorage(
    val sessionId: IStorageAdapter,
    val accessToken: IStorageAdapter,
    val refreshToken: IStorageAdapter,
    val idToken: IStorageAdapter
)

/**
 * Manages access / refresh / ID tokens and encrypted session data.
 * Concurrent refreshes are deduplicated via a shared Deferred and Mutex
 * (Android equivalent of the web Web Locks API).
 */
class AuthSessionManager(
    private val options: AuthSessionManagerOptions = AuthSessionManagerOptions()
) : IHttpSessionAuthProvider {

    constructor(
        apiClientConfig: ApiClientConfig? = null,
        storage: StorageConfig? = null,
        storageKeyPrefix: String = DEFAULT_STORAGE_KEY_PREFIX,
        accessTokenProvider: AccessTokenProvider? = null,
        context: Context? = null
    ) : this(
        AuthSessionManagerOptions(
            context = context,
            apiClientConfig = apiClientConfig,
            storage = storage,
            storageKeyPrefix = storageKeyPrefix,
            accessTokenProvider = accessTokenProvider
        )
    )

    private val accessTokenProvider: AccessTokenProvider? = options.accessTokenProvider
    private var sessionData: String? = null
    private val storageKeyPrefix: String = options.storageKeyPrefix
    private val storage: ResolvedStorage
    private var accessToken: String? = null
    private var _sessionId: String? = null
    private val config: ApiClientConfig? = options.apiClientConfig
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var refreshDeferred: Deferred<RefreshResponse>? = null
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val httpClient: OkHttpClient

    init {
        storage = resolveStorage(options.storage, options.context)
        val timeout = config?.timeout ?: 60_000L
        httpClient = OkHttpClient.Builder()
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val sessionIdKey get() = "$storageKeyPrefix:${STORAGE_KEYS.SESSION_ID}"
    private val accessTokenKey get() = "$storageKeyPrefix:${STORAGE_KEYS.ACCESS_TOKEN}"
    private val refreshTokenKey get() = "$storageKeyPrefix:${STORAGE_KEYS.REFRESH_TOKEN}"
    private val idTokenKey get() = "$storageKeyPrefix:${STORAGE_KEYS.ID_TOKEN}"

    override suspend fun getAccessToken(): String? {
        if (accessTokenProvider != null) {
            return accessTokenProvider.getAccessToken()
        }
        return getStoredAccessToken()
    }

    suspend fun getRefreshToken(): String? {
        return storage.refreshToken.get(refreshTokenKey)
    }

    suspend fun getIdToken(): String? {
        return storage.idToken.get(idTokenKey)
    }

    suspend fun getSessionId(): String? {
        if (_sessionId != null) return _sessionId
        return storage.sessionId.get(sessionIdKey)
    }

    suspend fun setTokens(tokens: AuthTokens) {
        if (tokens.sessionId != null) {
            require(HexUtils.isHexString(tokens.sessionId)) { "Session id must be a hex string" }
            _sessionId = HexUtils.add0x(HexUtils.padHexString(tokens.sessionId))
        }
        if (tokens.accessToken != null) {
            accessToken = tokens.accessToken
        }
        if (tokens.sessionId != null) {
            storage.sessionId.set(sessionIdKey, _sessionId!!)
        }
        if (tokens.accessToken != null) {
            storage.accessToken.set(accessTokenKey, tokens.accessToken)
        }
        if (tokens.refreshToken != null) {
            storage.refreshToken.set(refreshTokenKey, tokens.refreshToken)
        }
        if (tokens.idToken != null) {
            storage.idToken.set(idTokenKey, tokens.idToken)
        }
    }

    suspend fun setAccessToken(token: String) {
        accessToken = token
        storage.accessToken.set(accessTokenKey, token)
    }

    suspend fun setRefreshToken(token: String) {
        storage.refreshToken.set(refreshTokenKey, token)
    }

    fun isAuthenticated(): Boolean = sessionData != null

    suspend fun clearSessionData() {
        accessToken = null
        _sessionId = null
        sessionData = null
        storage.sessionId.remove(sessionIdKey)
        storage.accessToken.remove(accessTokenKey)
        storage.refreshToken.remove(refreshTokenKey)
        storage.idToken.remove(idTokenKey)
    }

    suspend fun getState(): SessionState {
        return SessionState(
            isAuthenticated = isAuthenticated(),
            sessionId = getSessionId()
        )
    }

    /**
     * Restore session by refreshing tokens.
     * Always calls the API to get session_data.
     * Returns decrypted session_data if successful, null if re-authentication is needed.
     */
    suspend fun authorize(): String? {
        requireConfig()
        val sessionId = getSessionId()
        val token = getAccessToken()
        val refreshToken = getRefreshToken()
        if (sessionId != null && (token != null || refreshToken != null)) {
            try {
                val response = ensureRefresh(false)
                sessionData = response.session_data
                return decryptSessionData(sessionId, response.session_data)
            } catch (_: Exception) {
                clearSessionData()
            }
        }
        return null
    }

    /**
     * Ensure a refresh is in progress or trigger one.
     * Reuses the in-flight Deferred so concurrent callers share a single API call.
     */
    suspend fun ensureRefresh(skipIfFresh: Boolean = false): RefreshResponse {
        requireConfig()
        var createdByUs = false
        val deferred = refreshMutex.withLock {
            refreshDeferred?.let { return@withLock it }
            val newDeferred = CompletableDeferred<RefreshResponse>()
            refreshDeferred = newDeferred
            createdByUs = true
            newDeferred
        }
        if (createdByUs) {
            try {
                (deferred as CompletableDeferred).complete(acquireLockAndRefresh(skipIfFresh))
            } catch (t: Throwable) {
                (deferred as CompletableDeferred).completeExceptionally(t)
            } finally {
                refreshMutex.withLock { refreshDeferred = null }
            }
        }
        return deferred.await()
    }

    override suspend fun handleUnauthorized(): String {
        val response = ensureRefresh(true)
        return response.access_token
    }

    suspend fun logout() {
        val cfg = requireConfig()
        refreshDeferred?.let { runCatching { it.await() } }
        val endpoint = "${cfg.baseURL}${cfg.logoutEndpoint}"
        try {
            val token = getAccessToken()
            val refreshToken = getRefreshToken()
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (token != null) {
                headers["Authorization"] = "Bearer $token"
            }
            val body = mutableMapOf<String, String>()
            if (refreshToken != null) {
                body["refresh_token"] = refreshToken
            }
            postJson(endpoint, body, headers)
        } catch (_: Exception) {
            // don't throw, just clear tokens
        }
        clearSessionData()
    }

    fun getAccessTokenAsync(): CompletableFuture<String?> = future { getAccessToken() }
    fun getRefreshTokenAsync(): CompletableFuture<String?> = future { getRefreshToken() }
    fun getIdTokenAsync(): CompletableFuture<String?> = future { getIdToken() }
    fun getSessionIdAsync(): CompletableFuture<String?> = future { getSessionId() }
    fun setTokensAsync(tokens: AuthTokens): CompletableFuture<Void> = futureVoid { setTokens(tokens) }
    fun authorizeAsync(): CompletableFuture<String?> = future { authorize() }
    fun logoutAsync(): CompletableFuture<Void> = futureVoid { logout() }
    fun ensureRefreshAsync(skipIfFresh: Boolean = false): CompletableFuture<RefreshResponse> =
        future { ensureRefresh(skipIfFresh) }
    fun handleUnauthorizedAsync(): CompletableFuture<String> = future { handleUnauthorized() }
    fun clearSessionDataAsync(): CompletableFuture<Void> = futureVoid { clearSessionData() }
    fun getStateAsync(): CompletableFuture<SessionState> = future { getState() }

    private suspend fun getStoredAccessToken(): String? {
        if (accessToken != null) return accessToken
        return storage.accessToken.get(accessTokenKey)
    }

    private fun resolveStorage(storageConfig: StorageConfig?, context: Context?): ResolvedStorage {
        val tokenAdapter: IStorageAdapter = when {
            context != null -> EncryptedSharedPreferencesAdapter(context, "${storageKeyPrefix}_tokens")
            else -> MemoryStorageAdapter()
        }
        val refreshAdapter: IStorageAdapter = when {
            context != null -> EncryptedSharedPreferencesAdapter(context, "${storageKeyPrefix}_refresh")
            else -> MemoryStorageAdapter()
        }
        return ResolvedStorage(
            sessionId = storageConfig?.sessionId ?: tokenAdapter,
            accessToken = storageConfig?.accessToken ?: tokenAdapter,
            refreshToken = storageConfig?.refreshToken ?: refreshAdapter,
            idToken = storageConfig?.idToken ?: tokenAdapter
        )
    }

    private fun requireConfig(): ApiClientConfig {
        return config ?: throw AuthError(
            "apiClientConfig is required for API operations",
            AuthErrorCode.INVALID
        )
    }

    private suspend fun acquireLockAndRefresh(skipIfFresh: Boolean): RefreshResponse {
        if (skipIfFresh) {
            val cached = tryResolveFreshFromStorage()
            if (cached != null) return cached
        }
        return performRefresh()
    }

    /**
     * If another caller refreshed, the token in storage will differ from our
     * in-memory copy. Return a synthetic response so we can skip the API call.
     */
    private suspend fun tryResolveFreshFromStorage(): RefreshResponse? {
        val storedAccessToken = storage.accessToken.get(accessTokenKey)
        if (storedAccessToken != null && storedAccessToken != accessToken) {
            accessToken = storedAccessToken
            return RefreshResponse(
                access_token = storedAccessToken,
                refresh_token = getRefreshToken() ?: "",
                session_data = sessionData ?: ""
            )
        }
        return null
    }

    private suspend fun performRefresh(): RefreshResponse {
        val cfg = requireConfig()
        val token = getAccessToken()
        val refreshToken = getRefreshToken()
        if (token == null && refreshToken == null) {
            throw AuthError("No access token or refresh token available", AuthErrorCode.EXPIRED)
        }
        val endpoint = "${cfg.baseURL}${cfg.sessionsEndpoint}"
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (token != null) {
            headers["Authorization"] = "Bearer $token"
        }
        val body = mutableMapOf<String, String>()
        if (refreshToken != null) {
            body["refresh_token"] = refreshToken
        }
        try {
            val raw = postJson(endpoint, body, headers)
            val response = gson.fromJson(raw, RefreshResponse::class.java)
                ?: throw AuthError("Token refresh failed", AuthErrorCode.REFRESH_FAILED)
            if (response.access_token.isNotEmpty()) {
                setAccessToken(response.access_token)
            }
            if (response.refresh_token.isNotEmpty()) {
                setRefreshToken(response.refresh_token)
            }
            return response
        } catch (e: AuthError) {
            if (e.code == AuthErrorCode.EXPIRED) throw e
            throw AuthError("Token refresh failed", AuthErrorCode.REFRESH_FAILED)
        } catch (_: Exception) {
            throw AuthError("Token refresh failed", AuthErrorCode.REFRESH_FAILED)
        }
    }

    private fun decryptSessionData(sessionIdHex: String, data: String): String {
        return try {
            SessionCrypto.decrypt(sessionIdHex, data)
        } catch (e: Exception) {
            throw AuthError("There was an error decrypting data.", AuthErrorCode.DECRYPT_FAILED)
        }
    }

    private suspend fun postJson(
        url: String,
        body: Map<String, String>,
        headers: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val json = gson.toJson(body)
        val requestBuilder = Request.Builder()
            .url(url)
            .post(json.toRequestBody(mediaType))
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AuthError("HTTP ${response.code}", AuthErrorCode.NETWORK)
            }
            responseBody
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

    private fun futureVoid(block: suspend () -> Unit): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        scope.launch {
            try {
                block()
                result.complete(null)
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result
    }
}
