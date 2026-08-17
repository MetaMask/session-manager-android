package com.web3auth.session_manager_android

import com.web3auth.session_manager_android.interfaces.IStorageManager
import com.web3auth.session_manager_android.util.HexUtils

abstract class BaseStorageManager : IStorageManager {
    protected var _sessionId: String? = null

    val sessionId: String?
        get() = _sessionId

    fun checkSessionParams() {
        if (_sessionId.isNullOrEmpty()) {
            throw Exception("Session id is required")
        }
        _sessionId = HexUtils.add0x(HexUtils.padHexString(_sessionId!!))
    }

    abstract fun setSessionId(sessionId: String)
}
