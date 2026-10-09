package com.jarves.mh.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyVault(context: Context) {
    private val preferences = context.getSharedPreferences("pocket_secrets", Context.MODE_PRIVATE)
    private val alias = "pocket-provider-key"

    /**
     * Stores a secret for [providerId] WITHOUT ever silently swapping an
     * existing pooled key's secret:
     *
     * - empty pool -> creates the first "Primary" entry (active);
     * - the active entry already stores this exact secret -> idempotent no-op;
     * - a DIFFERENT secret -> added as its own NEW pool entry (default name
     *   "API key N") and activated; every previously saved key keeps its
     *   secret untouched.
     *
     * Returns false when the secret is blank or AndroidKeyStore fails —
     * callers surface that instead of crashing mid-onboarding on flaky
     * keystore devices.
     */
    @Synchronized
    fun put(providerId: String, secret: String): Boolean {
        if (secret.isBlank()) return false
        return runCatching {
            val entries = ensurePool(providerId)
            val active = entries.firstOrNull { it.id == activeId(providerId) } ?: entries.firstOrNull()
            val activeSecret = active?.let { getEncrypted(secretKey(providerId, it.id)) }
            when (val action = resolvePut(entries.size, activeSecret, secret)) {
                PutAction.NoOp -> true
                is PutAction.AddEntry -> add(providerId, action.name, secret) != null
            }
        }.getOrDefault(false)
    }

    /**
     * Adds a new pool entry and makes it the ACTIVE key. Returns null when
     * the secret is blank or the key cannot be stored securely.
     */
    @Synchronized
    fun add(providerId: String, name: String, secret: String): ApiKeyInfo? {
        if (secret.isBlank()) return null
        return runCatching {
            val entries = ensurePool(providerId).toMutableList()
            val entry = ApiKeyInfo(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "API key ${entries.size + 1}" }.take(60),
            )
            putEncrypted(secretKey(providerId, entry.id), secret)
            entries += entry
            savePool(providerId, entries)
            // A newly added key becomes the active one: users expect the key
            // they just added to be the one the next request uses, without a
            // separate activation tap.
            setActiveId(providerId, entry.id)
            entry.copy(isActive = true)
        }.getOrNull()
    }

    @Synchronized
    fun list(providerId: String): List<ApiKeyInfo> {
        val entries = ensurePool(providerId)
        val active = activeId(providerId) ?: entries.firstOrNull()?.id
        return entries.map { it.copy(isActive = it.id == active) }
    }

    @Synchronized
    fun credentials(providerId: String): List<ApiKeyCredential> = list(providerId).mapNotNull { info ->
        getEncrypted(secretKey(providerId, info.id))?.let { secret ->
            ApiKeyCredential(info.id, info.name, secret, info.isActive)
        }
    }.sortedByDescending(ApiKeyCredential::isActive)

    @Synchronized
    fun activate(providerId: String, keyId: String): Boolean {
        if (ensurePool(providerId).none { it.id == keyId }) return false
        setActiveId(providerId, keyId)
        return true
    }

    @Synchronized
    fun remove(providerId: String, keyId: String) {
        val remaining = ensurePool(providerId).filterNot { it.id == keyId }
        removeEncrypted(secretKey(providerId, keyId))
        savePool(providerId, remaining)
        if (activeId(providerId) == keyId) {
            preferences.edit().remove(activeKey(providerId)).apply()
            remaining.firstOrNull()?.let { setActiveId(providerId, it.id) }
        }
    }

    private fun putEncrypted(storageId: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("$storageId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$storageId.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun contains(providerId: String): Boolean = get(providerId) != null

    @Synchronized
    fun remove(providerId: String) {
        ensurePool(providerId).forEach { removeEncrypted(secretKey(providerId, it.id)) }
        preferences.edit()
            .remove("$providerId.iv")
            .remove("$providerId.value")
            .remove(poolKey(providerId))
            .remove(activeKey(providerId))
            .apply()
    }

    @Synchronized
    fun get(providerId: String): String? {
        val active = list(providerId).firstOrNull { it.isActive } ?: return null
        return getEncrypted(secretKey(providerId, active.id))
    }

    private fun getEncrypted(storageId: String): String? = runCatching {
        val iv = Base64.decode(preferences.getString("$storageId.iv", null), Base64.NO_WRAP)
        val encrypted = Base64.decode(preferences.getString("$storageId.value", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun removeEncrypted(storageId: String) {
        preferences.edit().remove("$storageId.iv").remove("$storageId.value").apply()
    }

    private fun ensurePool(providerId: String): List<ApiKeyInfo> {
        readPool(providerId).takeIf(List<ApiKeyInfo>::isNotEmpty)?.let { return it }
        if (!preferences.contains("$providerId.value")) return emptyList()
        val legacySecret = getEncrypted(providerId) ?: return emptyList()
        val legacy = ApiKeyInfo("legacy", "Primary", true)
        putEncrypted(secretKey(providerId, legacy.id), legacySecret)
        savePool(providerId, listOf(legacy))
        setActiveId(providerId, legacy.id)
        return listOf(legacy)
    }

    private fun readPool(providerId: String): List<ApiKeyInfo> = runCatching {
        val array = JSONArray(preferences.getString(poolKey(providerId), "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                if (id.isBlank()) null else ApiKeyInfo(id, item.optString("name").ifBlank { "API key" })
            }
        }
    }.getOrDefault(emptyList())

    private fun savePool(providerId: String, entries: List<ApiKeyInfo>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences.edit().putString(poolKey(providerId), array.toString()).apply()
    }

    private fun activeId(providerId: String): String? = preferences.getString(activeKey(providerId), null)
    private fun setActiveId(providerId: String, id: String) = preferences.edit().putString(activeKey(providerId), id).apply()
    private fun poolKey(providerId: String) = "$providerId.pool"
    private fun activeKey(providerId: String) = "$providerId.active"
    private fun secretKey(providerId: String, id: String) = "$providerId.pool.$id"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

data class ApiKeyInfo(val id: String, val name: String, val isActive: Boolean = false)

data class ApiKeyCredential(val id: String, val name: String, val secret: String, val isActive: Boolean)

/** Decision produced by [resolvePut] — a pure function so the no-silent-overwrite contract is unit-testable. */
internal sealed interface PutAction {
    /** The active entry already stores this exact secret; nothing to do. */
    data object NoOp : PutAction

    /** Store the secret as a NEW pool entry (never overwrite an existing one). */
    data class AddEntry(val name: String) : PutAction
}

/**
 * Resolves what [ApiKeyVault.put] must do for a pool of [poolSize] entries
 * whose active entry currently holds [activeSecret] when asked to store
 * [secret]. A different secret NEVER overwrites the active entry — it becomes
 * its own new entry, keeping every existing saved key intact.
 */
internal fun resolvePut(poolSize: Int, activeSecret: String?, secret: String): PutAction = when {
    poolSize == 0 -> PutAction.AddEntry("Primary")
    activeSecret == secret -> PutAction.NoOp
    else -> PutAction.AddEntry("API key ${poolSize + 1}")
}
