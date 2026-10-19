package com.jarves.mh.zweb

import org.json.JSONObject

/**
 * v1.0.35 — pure chat-id extraction and per-project conversation memory.
 *
 * WHY (real-phone evidence, v1.0.34): the file-sync probe read the Z.ai
 * conversation id ONLY from the hidden WebView's URL (`/c/<id>`). On the
 * user's phone the sync ran when the URL no longer carried the id (the SPA
 * re-navigates; a reload lands on `/`), so the probe bailed with
 * "no chat id in URL" and 0 files were read.
 *
 * LIVE-SITE EVIDENCE (2026-10-19, headless Chromium 412x915 mobile viewport,
 * scripts/probe_chatid_v1035.js, guest):
 *  - VERIFIED: sending the first message makes the SPA call
 *    `POST /api/v1/chats/new` and ~2 s later the URL becomes `/c/<uuid>`.
 *  - VERIFIED: localStorage carries NO conversation id (checked all keys).
 *  - VERIFIED: the site polls/reads chat detail under
 *    `/api/v1/chats/<uuid>` once inside a conversation, so the browser's
 *    performance resource entries expose the id even when the URL does not.
 *  - GUESSED (needs a signed-in phone): whether pushState is ever SKIPPED on
 *    a real signed-in account. Defensive design regardless: capture the id
 *    the moment it appears, store it per project chat, and reuse it so sync
 *    never depends on the current URL.
 *
 * The id shape is a UUID-ish token (site-verified: a7484009-782c-4eee-…).
 * Everything here is pure JVM — the engine and the ViewModel only glue it.
 */
object ZaiChatId {

    /** A captured Z.ai conversation id (validated shape). */
    fun isValid(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val s = candidate.trim()
        if (s.length !in 8..64) return false
        return s.all { it.isLetterOrDigit() || it == '-' }
    }

    /** Extracts the id from a page URL containing `/c/<id>` (any host). */
    fun fromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val m = Regex("/c/([0-9a-fA-F-]{8,})").find(url) ?: return null
        val id = m.groupValues[1]
        return id.takeIf { isValid(it) }
    }

    /**
     * Extracts the id from an API resource URL (chat detail fetched by the
     * site itself) or a `chat_id=<id>` query parameter — the browser
     * performance-entries channel.
     */
    fun fromResourceUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val m = Regex("/api/v1/chats/([0-9a-fA-F-]{8,})").find(url)
            ?: Regex("[?&]chat_id=([0-9a-fA-F-]{8,})").find(url)
            ?: return null
        val id = m.groupValues[1]
        return id.takeIf { isValid(it) }
    }

    /** First valid id among the raw candidates (order = priority). */
    fun pick(candidates: List<String?>): String? = candidates.firstOrNull { isValid(it) }

    // ── Per-project store (persisted by [ZaiChatIdStore]) ──

    data class Entry(
        val appChatId: String,
        val zaiChatId: String,
        val updatedAtMillis: Long,
        /** How the id was captured — "url" | "perf" | "probe" (diagnostics only). */
        val source: String,
    )

    /** Pure store logic: upsert (newer wins), prune oldest beyond [MAX_ENTRIES]. */
    object MapPolicy {
        const val MAX_ENTRIES = 50

        fun upsert(
            current: List<Entry>,
            appChatId: String,
            zaiChatId: String,
            nowMillis: Long,
            source: String,
        ): List<Entry> {
            if (!isValid(zaiChatId) || appChatId.isBlank()) return current
            val rest = current.filter { it.appChatId != appChatId }
            val updated = (rest + Entry(appChatId.trim(), zaiChatId.trim(), nowMillis.coerceAtLeast(0), source))
                .sortedByDescending { it.updatedAtMillis }
            return if (updated.size > MAX_ENTRIES) updated.take(MAX_ENTRIES) else updated
        }

        fun get(current: List<Entry>, appChatId: String): String? =
            current.firstOrNull { it.appChatId == appChatId }?.zaiChatId
    }

    // ── JSON round-trip (tolerant, mirrors ZaiTaskQueue's loader style) ──

    fun toJson(entries: List<Entry>): String {
        val arr = org.json.JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("appChatId", e.appChatId)
                    .put("zaiChatId", e.zaiChatId)
                    .put("updatedAtMillis", e.updatedAtMillis)
                    .put("source", e.source),
            )
        }
        return JSONObject().put("version", 1).put("entries", arr).toString()
    }

    /** Never throws: malformed lines/entries are skipped, the rest survive. */
    fun fromJson(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("entries") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val app = o.optString("appChatId", "")
                val zai = o.optString("zaiChatId", "")
                if (!isValid(zai) || app.isBlank()) return@mapNotNull null
                Entry(
                    appChatId = app,
                    zaiChatId = zai,
                    updatedAtMillis = when (val ts = o.opt("updatedAtMillis")) {
                        is Number -> ts.toLong()
                        is String -> ts.toLongOrNull() ?: 0L
                        else -> 0L
                    },
                    source = o.optString("source", "unknown").take(20),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * v1.0.35 — atomic persisted store for the per-project Z.ai conversation ids
 * (`filesDir/zweb/chat-ids.json`), same tmp+rename model as ZaiTaskQueueStore.
 * Nothing on the startup path reads it: the store is opened lazily by the
 * ViewModel on the first Z.ai turn / manual sync.
 */
class ZaiChatIdStore(private val file: java.io.File) {

    constructor(context: android.content.Context) : this(
        java.io.File(java.io.File(context.filesDir, "zweb"), "chat-ids.json"),
    )

    private val lock = Any()

    fun load(): List<ZaiChatId.Entry> = synchronized(lock) {
        runCatching { ZaiChatId.fromJson(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(entries: List<ZaiChatId.Entry>) {
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = java.io.File(file.parentFile, file.name + ".tmp")
                tmp.writeText(ZaiChatId.toJson(entries))
                if (!tmp.renameTo(file)) {
                    file.writeText(ZaiChatId.toJson(entries))
                    tmp.delete()
                }
            }
        }
    }

    fun upsert(appChatId: String, zaiChatId: String, nowMillis: Long, source: String) {
        synchronized(lock) {
            val updated = ZaiChatId.MapPolicy.upsert(load(), appChatId, zaiChatId, nowMillis, source)
            save(updated)
        }
    }

    fun get(appChatId: String): String? = synchronized(lock) {
        ZaiChatId.MapPolicy.get(load(), appChatId)
    }
}
