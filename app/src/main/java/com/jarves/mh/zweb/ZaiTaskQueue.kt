package com.jarves.mh.zweb

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persisted task queue for the Z.ai chat engine (Phase 3). Every chat-engine
 * turn is enqueued before it is sent; the queue outlives the process so a
 * killed app can either RESUME a task that never started or REPORT cleanly
 * about a task that was interrupted mid-run.
 *
 * The pure queue operations are JVM-tested; [ZaiTaskQueueStore] adds the
 * file persistence (atomic write + tolerant read).
 */
object ZaiTaskQueue {

    enum class Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    const val MAX_ENTRIES = 50
    const val MAX_PROMPT_CHARS = 8_000
    const val MAX_ERROR_CHARS = 300

    /**
     * A queued Z.ai task. [prompt] is the FULL assembled prompt (context
     * included) so a resume needs no workspace re-reading; it is redacted by
     * the same egress rule as everything else and capped in size — the queue
     * is a recovery aid, not an archive.
     */
    data class Entry(
        val id: String,
        val projectId: String,
        val chatId: String,
        /** Human task title for notifications (never the full prompt). */
        val title: String,
        /** The full prompt to (re-)send. */
        val prompt: String,
        val continueChat: Boolean,
        val status: Status,
        val enqueuedAtMillis: Long,
        val startedAtMillis: Long? = null,
        val finishedAtMillis: Long? = null,
        val attempts: Int = 0,
        val lastError: String? = null,
    )

    // ── Pure operations ──

    fun enqueue(entries: List<Entry>, projectId: String, chatId: String, title: String, prompt: String, continueChat: Boolean, nowMillis: Long): List<Entry> {
        val entry = Entry(
            id = "zq-${nowMillis}-${(entries.size + 1)}",
            projectId = projectId,
            chatId = chatId,
            title = title.take(120).ifBlank { "Z.ai task" },
            prompt = prompt.take(MAX_PROMPT_CHARS),
            continueChat = continueChat,
            status = Status.PENDING,
            enqueuedAtMillis = nowMillis,
        )
        return (entries + entry).takeLast(MAX_ENTRIES)
    }

    fun markRunning(entries: List<Entry>, id: String, nowMillis: Long): List<Entry> = update(entries, id) {
        it.copy(status = Status.RUNNING, startedAtMillis = nowMillis, attempts = it.attempts + 1, lastError = null)
    }

    fun markTerminal(entries: List<Entry>, id: String, status: Status, error: String?, nowMillis: Long): List<Entry> =
        update(entries, id) {
            require(status in setOf(Status.DONE, Status.FAILED, Status.CANCELLED)) { "not a terminal status: $status" }
            it.copy(
                status = status,
                finishedAtMillis = nowMillis,
                lastError = error?.take(MAX_ERROR_CHARS)?.ifBlank { null },
            )
        }

    private fun update(entries: List<Entry>, id: String, transform: (Entry) -> Entry): List<Entry> =
        entries.map { if (it.id == id) transform(it) else it }

    /**
     * Startup reconciliation (called once per process): a RUNNING entry means
     * the process died mid-turn — REPORT it as failed. A PENDING entry means
     * the turn never started; per [resumeDecision] it is either re-sent
     * (fresh crash, signed-in) or reported as failed too. DONE/FAILED/
     * CANCELLED pass through untouched.
     */
    data class ReconcileResult(
        val entries: List<Entry>,
        /** Entries transitioned to FAILED with a human reason (for a visible report). */
        val reported: List<Entry>,
        /** Entries that should be re-sent automatically (fresh crash window, signed in). */
        val toResume: List<Entry>,
    )

    fun reconcile(entries: List<Entry>, nowMillis: Long, signedIn: Boolean): ReconcileResult {
        val reported = ArrayList<Entry>()
        val toResume = ArrayList<Entry>()
        val next = entries.map { entry ->
            when (entry.status) {
                Status.RUNNING -> {
                    val marked = entry.copy(
                        status = Status.FAILED,
                        finishedAtMillis = nowMillis,
                        lastError = "The app was killed before this task finished. Send it again to continue.",
                    )
                    reported.add(marked)
                    marked
                }
                Status.PENDING -> when (resumeDecision(entry, nowMillis, signedIn)) {
                    ResumeDecision.RESUME -> {
                        toResume.add(entry)
                        entry
                    }
                    ResumeDecision.REPORT_FAILED -> {
                        val marked = entry.copy(
                            status = Status.FAILED,
                            finishedAtMillis = nowMillis,
                            lastError = "Queued but never started (the app restarted before sending). Send it again.",
                        )
                        reported.add(marked)
                        marked
                    }
                }
                else -> entry
            }
        }
        return ReconcileResult(entries = next, reported = reported, toResume = toResume)
    }

    enum class ResumeDecision { RESUME, REPORT_FAILED }

    /**
     * A PENDING task is resumed only when the crash was recent AND the
     * engine is signed in (the turn can actually be re-sent). Anything else
     * is reported, never silently retried.
     */
    const val RESUME_WINDOW_MILLIS = 10 * 60_000L

    fun resumeDecision(entry: Entry, nowMillis: Long, signedIn: Boolean): ResumeDecision =
        if (signedIn && nowMillis - entry.enqueuedAtMillis <= RESUME_WINDOW_MILLIS) {
            ResumeDecision.RESUME
        } else {
            ResumeDecision.REPORT_FAILED
        }

    /** Entries worth keeping on disk: recent + unfinished, or finished within the last day. */
    fun prune(entries: List<Entry>, nowMillis: Long): List<Entry> {
        val day = 24 * 60 * 60_000L
        return entries.filter { entry ->
            when (entry.status) {
                Status.DONE, Status.FAILED, Status.CANCELLED ->
                    (entry.finishedAtMillis ?: 0L) > nowMillis - day
                else -> true
            }
        }.takeLast(MAX_ENTRIES)
    }

    // ── JSON (de)serialization ──

    fun toJson(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
                .put("id", e.id)
                .put("projectId", e.projectId)
                .put("chatId", e.chatId)
                .put("title", e.title)
                .put("prompt", e.prompt)
                .put("continueChat", e.continueChat)
                .put("status", e.status.name)
                .put("enqueuedAt", e.enqueuedAtMillis)
                .put("attempts", e.attempts)
            e.startedAtMillis?.let { o.put("startedAt", it) }
            e.finishedAtMillis?.let { o.put("finishedAt", it) }
            e.lastError?.let { o.put("lastError", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun fromJson(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "")
            val prompt = o.optString("prompt", "")
            if (id.isBlank() || prompt.isBlank()) return@mapNotNull null
            Entry(
                id = id,
                projectId = o.optString("projectId", ""),
                chatId = o.optString("chatId", ""),
                title = o.optString("title", "Z.ai task"),
                prompt = prompt,
                continueChat = o.optBoolean("continueChat", false),
                status = runCatching { Status.valueOf(o.optString("status", "PENDING")) }.getOrDefault(Status.PENDING),
                enqueuedAtMillis = o.optLong("enqueuedAt", 0L),
                startedAtMillis = if (o.has("startedAt")) o.optLong("startedAt") else null,
                finishedAtMillis = if (o.has("finishedAt")) o.optLong("finishedAt") else null,
                attempts = o.optInt("attempts", 0),
                lastError = o.optString("lastError", "").ifBlank { null },
            )
        }
    }
}

/** File-backed store for [ZaiTaskQueue]; atomic write, tolerant read. */
class ZaiTaskQueueStore(private val file: File) {

    @Synchronized
    fun load(): List<ZaiTaskQueue.Entry> = ZaiTaskQueue.fromJson(runCatching {
        if (file.isFile) file.readText() else null
    }.getOrNull())

    @Synchronized
    fun save(entries: List<ZaiTaskQueue.Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ZaiTaskQueue.toJson(entries))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
