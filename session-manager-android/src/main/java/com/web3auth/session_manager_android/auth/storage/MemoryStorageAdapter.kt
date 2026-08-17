package com.web3auth.session_manager_android.auth.storage

class MemoryStorageAdapter : IStorageAdapter {
    private val store = LinkedHashMap<String, String>()

    override suspend fun get(key: String): String? = store[key]

    override suspend fun set(key: String, value: String) {
        store[key] = value
    }

    override suspend fun remove(key: String) {
        store.remove(key)
    }

    fun clear() {
        store.clear()
    }
}
