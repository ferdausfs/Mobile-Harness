package com.jarves.mh.tokens

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

/**
 * One stored token's METADATA. The secret value NEVER appears here — it only
 * exists inside [TokenVault.value]/[TokenVault.valuesForProject] results and
 * is redacted out of everything else by [SecretRedactor].
 */
data class StoredToken(
    val id: String,
    val presetId: String,
    /** User-visible name, e.g. "GitHub main" or a custom label. */
    val name: String,
    /** Primary env var name (custom tokens define their own). */
    val envVar: String,
    /** Enabled globally; the per-project set narrows further (empty = all projects). */
    val enabled: Boolean = true,
    /** Optional: disable this token automatically after every task that used it. */
    val autoDisableAfterTask: Boolean = false,
    /** Project ids this token is available to. Empty = available to every project. */
    val allowedProjects: Set<String> = emptySet(),
    /** Rotation badge state (set by TokenRotation.onTaskEnded, cleared by Rotated/replace). */
    val needsRotation: Boolean = false,
    val rotationReason: String? = null,
    val rotationAtMillis: Long? = null,
    val createdAtMillis: Long = 0L,
)

/**
 * Encrypted storage for named tokens (GitHub, Cloudflare, Vercel, Firebase,
 * Supabase, npm, custom). Same AndroidKeyStore scheme as
 * [com.jarves.mh.data.ApiKeyVault]: AES/GCM with a non-exportable
 * AndroidKeyStore key, IV + ciphertext in SharedPreferences. Values are only
 * ever produced through [value] / [valuesForProject]; every egress path must
 * pass them through [SecretRedactor].
 */
class TokenVault(context: Context) {

    private val preferences = context.getSharedPreferences("mh_tokens", Context.MODE_PRIVATE)
    private val keystoreAlias = "mh-token-key"

    // ── CRUD ──

    /** Stores a new token. Returns null when the value is blank or Keystore fails. */
    @Synchronized
    fun add(presetId: String, name: String, envVar: String, value: String): StoredToken? {
        if (value.isBlank()) return null
        val preset = TokenPresets.byId(presetId)
        val resolvedEnv = (envVar.ifBlank { preset.envVars.firstOrNull() ?: "" }).trim().uppercase()
        if (!TokenPresets.isValidEnvVarName(resolvedEnv)) return null
        return runCatching {
            val token = StoredToken(
                id = UUID.randomUUID().toString(),
                presetId = preset.id,
                name = name.trim().ifBlank { preset.title }.take(60),
                envVar = resolvedEnv,
                createdAtMillis = System.currentTimeMillis(),
            )
            putEncrypted(token.id, value.trim())
            saveRecords(records + token)
            token
        }.getOrNull()
    }

    /** Replaces the value of an existing token; replacing clears the rotation badge. */
    @Synchronized
    fun updateValue(id: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (records.none { it.id == id }) return false
        return runCatching {
            putEncrypted(id, value.trim())
            saveRecords(
                records.map {
                    if (it.id == id) TokenRotation.markReplaced(it) else it
                },
            )
            true
        }.getOrDefault(false)
    }

    /** Removes a token (user-tapped only — nothing here ever auto-deletes). */
    @Synchronized
    fun remove(id: String) {
        runCatching { removeEncrypted(id) }
        saveRecords(records.filterNot { it.id == id })
    }

    // ── Reading ──

    /** Metadata list, newest first. No secret values. */
    @Synchronized
    fun list(): List<StoredToken> = records.sortedByDescending(StoredToken::createdAtMillis)

    fun contains(presetId: String): Boolean = records.any { it.presetId == presetId }

    /** Decrypts one token's value; null when missing or Keystore fails. */
    @Synchronized
    fun value(id: String): String? = getEncrypted(id)

    /**
     * The tokens a task on [projectId] would use (enabled + per-project allow
     * list), paired with their decrypted values keyed by env var name.
     */
    @Synchronized
    fun valuesForProject(projectId: String?): Map<String, String> {
        val usable = TokenRotation.availableForProject(records, projectId)
        val out = LinkedHashMap<String, String>()
        usable.forEach { token ->
            getEncrypted(token.id)?.let { v -> out[token.envVar] = v }
        }
        return out
    }

    /** All stored values — ONLY for [SecretRedactor] input. Never log or send. */
    @Synchronized
    fun allValues(): List<String> = records.mapNotNull { getEncrypted(it.id) }

    // ── Toggles ──

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        mutate(id) { it.copy(enabled = enabled) }
    }

    @Synchronized
    fun setAutoDisable(id: String, on: Boolean) {
        mutate(id) { it.copy(autoDisableAfterTask = on) }
    }

    @Synchronized
    fun setAllowedProjects(id: String, projects: Set<String>) {
        mutate(id) { it.copy(allowedProjects = projects) }
    }

    // ── Rotation state (persisted so badges survive restarts) ──

    @Synchronized
    fun applyTaskEnd(usedTokens: List<StoredToken>, taskTitle: String, nowMillis: Long) {
        val decision = TokenRotation.onTaskEnded(usedTokens, taskTitle)
        saveRecords(TokenRotation.apply(records, decision, nowMillis))
    }

    @Synchronized
    fun markRotated(id: String) {
        mutate(id) { TokenRotation.markRotated(it) }
    }

    @Synchronized
    fun flagged(): List<StoredToken> = records.filter { it.needsRotation }

    // ── Internals ──

    private val records: List<StoredToken>
        get() = runCatching {
            val array = JSONArray(preferences.getString(RECORDS_KEY, "[]"))
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val env = o.optString("envVar", "")
                if (env.isBlank()) return@mapNotNull null
                StoredToken(
                    id = o.optString("id"),
                    presetId = o.optString("presetId", TokenPresets.CUSTOM_ID),
                    name = o.optString("name", "Token"),
                    envVar = env,
                    enabled = o.optBoolean("enabled", true),
                    autoDisableAfterTask = o.optBoolean("autoDisable", false),
                    allowedProjects = buildSet {
                        val arr = o.optJSONArray("projects") ?: JSONArray()
                        for (j in 0 until arr.length()) arr.optString(j)?.takeIf(String::isNotBlank)?.let { add(it) }
                    },
                    needsRotation = o.optBoolean("needsRotation", false),
                    rotationReason = o.optString("rotationReason", "").ifBlank { null },
                    rotationAtMillis = if (o.has("rotationAt")) o.optLong("rotationAt") else null,
                    createdAtMillis = o.optLong("createdAt", 0L),
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())

    private fun saveRecords(list: List<StoredToken>) {
        val array = JSONArray()
        list.forEach { token ->
            val o = JSONObject()
                .put("id", token.id)
                .put("presetId", token.presetId)
                .put("name", token.name)
                .put("envVar", token.envVar)
                .put("enabled", token.enabled)
                .put("autoDisable", token.autoDisableAfterTask)
                .put("projects", JSONArray(token.allowedProjects.toList()))
                .put("needsRotation", token.needsRotation)
                .put("rotationReason", token.rotationReason.orEmpty())
                .put("createdAt", token.createdAtMillis)
            token.rotationAtMillis?.let { o.put("rotationAt", it) }
            array.put(o)
        }
        preferences.edit().putString(RECORDS_KEY, array.toString()).apply()
    }

    private fun mutate(id: String, transform: (StoredToken) -> StoredToken) {
        saveRecords(records.map { if (it.id == id) transform(it) else it })
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

    private fun getEncrypted(storageId: String): String? = runCatching {
        val iv = preferences.getString("$storageId.iv", null) ?: return null
        val encrypted = preferences.getString("$storageId.value", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun removeEncrypted(storageId: String) {
        preferences.edit().remove("$storageId.iv").remove("$storageId.value").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val RECORDS_KEY = "records"
    }
}

/** Masked display for a token value: keeps the first 3 and last 3 chars only. */
fun maskTokenValue(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 8) return "••••••••"
    return trimmed.take(3) + "••••••" + trimmed.takeLast(3)
}
