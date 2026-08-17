package com.web3auth.session_manager_android.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedPrefsStorage(
    context: Context,
    prefsName: String
) : SimpleStorage {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getItem(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun setItem(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun removeItem(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun keys(): Set<String> {
        return prefs.all.keys
    }
}
