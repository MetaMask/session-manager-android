package com.web3auth.session_manager_android.auth

enum class AuthErrorCode {
    EXPIRED,
    INVALID,
    REFRESH_FAILED,
    NETWORK,
    DECRYPT_FAILED,
    NO_SESSION_ID,
    SESSION_EXPIRED
}

class AuthError(
    message: String,
    val code: AuthErrorCode
) : Exception(message)
