package com.example.delayedsecrect // <-- match your actual package name

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class Secret(
    val id: String,
    val name: String,
    val value: String,
    val delayMinutes: Int? = null // null = use the global delay from Settings
)

class SecretStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secret_store_encrypted",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAll(): List<Secret> {
        val raw = prefs.getString("secrets_json", "[]") ?: "[]"
        val array = JSONArray(raw)
        val result = mutableListOf<Secret>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                Secret(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                    delayMinutes = if (obj.has("delayMinutes")) obj.getInt("delayMinutes") else null
                )
            )
        }
        return result
    }

    fun add(name: String, value: String, delayMinutes: Int?) {
        val current = getAll().toMutableList()
        current.add(
            Secret(
                id = System.currentTimeMillis().toString(),
                name = name,
                value = value,
                delayMinutes = delayMinutes
            )
        )
        saveAll(current)
    }

    fun delete(id: String) {
        val current = getAll().filter { it.id != id }
        saveAll(current)
    }

    private fun saveAll(list: List<Secret>) {
        val array = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("name", it.name)
            obj.put("value", it.value)
            it.delayMinutes?.let { d -> obj.put("delayMinutes", d) }
            array.put(obj)
        }
        prefs.edit().putString("secrets_json", array.toString()).apply()
    }
}