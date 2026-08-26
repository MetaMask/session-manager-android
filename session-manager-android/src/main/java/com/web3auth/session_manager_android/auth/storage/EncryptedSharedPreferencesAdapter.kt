package com.web3auth.session_manager_android.auth.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedSharedPreferencesAdapter(
    context: Context,
    prefsName: String
) : IStorageAdapter {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun get(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun set(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).commit()
        } catch (_: Exception) {
            // Storage full or unavailable
        }
    }

    override suspend fun remove(key: String) {
        try {
            prefs.edit().remove(key).commit()
        } catch (_: Exception) {
            // Ignore
        }
    }
}
