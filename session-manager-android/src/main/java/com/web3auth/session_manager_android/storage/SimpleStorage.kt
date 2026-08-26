package com.web3auth.session_manager_android.storage

/**
 * Minimal key-value store used by [com.web3auth.session_manager_android.handlers.LocalStorageHandler].
 * Production uses EncryptedSharedPreferences; tests can inject an in-memory map.
 */
interface SimpleStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
    fun keys(): Set<String>
}

class MapStorage : SimpleStorage {
    private val store = LinkedHashMap<String, String>()

    override fun getItem(key: String): String? = store[key]

    override fun setItem(key: String, value: String) {
        store[key] = value
    }

    override fun removeItem(key: String) {
        store.remove(key)
    }

    override fun keys(): Set<String> = store.keys.toSet()

    fun clear() {
        store.clear()
    }
}
