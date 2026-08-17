package com.web3auth.session_manager_android.handlers

import com.google.gson.JsonParser
import com.web3auth.session_manager_android.interfaces.StorageHandler
import com.web3auth.session_manager_android.interfaces.StorageHandlerRetrieveOptions
import com.web3auth.session_manager_android.interfaces.StorageHandlerStoreOptions
import com.web3auth.session_manager_android.storage.SimpleStorage
import com.web3auth.session_manager_android.util.HexUtils

class LocalStorageHandler(
    private val storage: SimpleStorage,
    private val baseStorageKey: String
) : StorageHandler {

    override fun getStorageKey(key: String): String {
        return "$baseStorageKey${HexUtils.remove0x(key)}"
    }

    override suspend fun storeData(
        key: String,
        data: String,
        options: StorageHandlerStoreOptions
    ) {
        storage.setItem(getStorageKey(key), data)
    }

    override suspend fun retrieveData(
        key: String,
        options: StorageHandlerRetrieveOptions
    ): String? {
        val storageKey = getStorageKey(key)
        val localData = storage.getItem(storageKey) ?: return null
        return try {
            parseJson(localData)
            localData
        } catch (_: Exception) {
            storage.removeItem(storageKey)
            null
        }
    }

    override fun clearStorage(key: String) {
        storage.removeItem(getStorageKey(key))
    }

    override fun clearOrphanedData(baseKey: String) {
        val keyPrefix = "$baseStorageKey$baseKey"
        storage.keys()
            .filter { it.startsWith(keyPrefix) }
            .forEach { storage.removeItem(it) }
    }

    private fun parseJson(value: String) {
        val element = JsonParser.parseString(value)
        if (!element.isJsonObject && !element.isJsonArray) {
            throw IllegalArgumentException("Cached value is not JSON")
        }
    }
}
