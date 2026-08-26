package com.web3auth.session_manager_android.auth.storage

interface IStorageAdapter {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun remove(key: String)
}
