package com.web3auth.session_manager_android.auth

import android.content.Context
import com.web3auth.session_manager_android.auth.storage.IStorageAdapter

const val DEFAULT_SESSIONS_ENDPOINT = "/v1/auth/session"
const val DEFAULT_LOGOUT_ENDPOINT = "/v1/auth/logout"
const val DEFAULT_STORAGE_KEY_PREFIX = "w3a"

object STORAGE_KEYS {
    const val SESSION_ID = "session_id"
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val ID_TOKEN = "id_token"
}

data class AuthTokens(
    val sessionId: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val idToken: String? = null
)

data class RefreshResponse(
    val access_token: String = "",
    val refresh_token: String = "",
    val session_data: String = ""
)

data class SessionState(
    val isAuthenticated: Boolean,
    val sessionId: String?
)

fun interface AccessTokenProvider {
    suspend fun getAccessToken(): String?
}

data class ApiClientConfig(
    val baseURL: String,
    val timeout: Long? = null,
    val sessionsEndpoint: String = DEFAULT_SESSIONS_ENDPOINT,
    val logoutEndpoint: String = DEFAULT_LOGOUT_ENDPOINT
)

data class StorageConfig(
    val sessionId: IStorageAdapter? = null,
    val accessToken: IStorageAdapter? = null,
    val refreshToken: IStorageAdapter? = null,
    val idToken: IStorageAdapter? = null
)

data class AuthSessionManagerOptions(
    val context: Context? = null,
    val apiClientConfig: ApiClientConfig? = null,
    val storage: StorageConfig? = null,
    val storageKeyPrefix: String = DEFAULT_STORAGE_KEY_PREFIX,
    val accessTokenProvider: AccessTokenProvider? = null
)

interface IHttpSessionAuthProvider {
    suspend fun getAccessToken(): String?
    suspend fun handleUnauthorized(): String
}

data class HttpClientRequestOptions(
    val headers: Map<String, String> = emptyMap(),
    val authenticated: Boolean = false,
    val useAPIKey: Boolean? = null,
    val timeout: Long? = null,
    val isUrlEncodedData: Boolean? = null,
    val logTracingHeader: Boolean? = null
)
