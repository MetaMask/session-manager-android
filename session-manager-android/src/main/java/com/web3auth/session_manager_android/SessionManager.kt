package com.web3auth.session_manager_android

import android.content.Context
import com.web3auth.session_manager_android.interfaces.DEFAULT_SESSION_TIMEOUT

/**
 * Legacy name for [StorageManager]. Prefer [StorageManager] with an explicit
 * [sessionServerBaseUrl] — there is no silent URL fallback.
 */
@Deprecated(
    message = "Use StorageManager. sessionServerBaseUrl is now required.",
    replaceWith = ReplaceWith(
        "StorageManager(context, sessionServerBaseUrl, sessionTime, allowedOrigin, sessionId, sessionNamespace, useLocalStorage)",
        "com.web3auth.session_manager_android.StorageManager"
    )
)
class SessionManager(
    context: Context,
    sessionServerBaseUrl: String,
    sessionTime: Int = DEFAULT_SESSION_TIMEOUT,
    allowedOrigin: String = "*",
    sessionId: String? = null,
    sessionNamespace: String? = null,
    useLocalStorage: Boolean = false
) : StorageManager(
    context,
    sessionServerBaseUrl,
    sessionTime,
    allowedOrigin,
    sessionId,
    sessionNamespace,
    useLocalStorage
) {
    companion object {
        @JvmStatic
        fun generateRandomSessionKey(): String = StorageManager.generateRandomSessionKey()

        @JvmStatic
        fun getSessionIdFromStorage(): String = StorageManager.getSessionIdFromStorage()

        @JvmStatic
        fun deleteSessionIdFromStorage() = StorageManager.deleteSessionIdFromStorage()

        @JvmStatic
        fun saveSessionIdToStorage(sessionId: String) = StorageManager.saveSessionIdToStorage(sessionId)
    }
}
