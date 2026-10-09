package com.jarves.mh.data

import com.jarves.mh.model.ProviderKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Live state of a provider as seen by the in-app tracker. */
enum class ProviderLiveState { IDLE, ACTIVE, LIMIT, AUTH_ERROR, ERROR, SWITCHED }

/**
 * Immutable per-provider usage snapshot shown in the live status card.
 * Day counters roll over at UTC midnight; totals accumulate for the install.
 */
data class ProviderUsageSnapshot(
    val kind: ProviderKind,
    val dayRequests: Int = 0,
    val dayTasks: Int = 0,
    val dayInputTokens: Long = 0,
    val dayOutputTokens: Long = 0,
    val totalRequests: Long = 0,
    val totalTasks: Long = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val state: ProviderLiveState = ProviderLiveState.IDLE,
    val lastMessage: String = "",
    val lastUpdateAtMillis: Long = 0,
    /** User-configured daily upstream-request budget. 0 means unset. */
    val dailyRequestLimit: Int = 0,
    /** Raw JSON returned by the provider's usage/account endpoints (Ollama Cloud). */
    val remoteUsageJson: String? = null,
    val remoteUsageFetchedAtMillis: Long = 0,
    /**
     * False when the active runtime/provider route cannot report real per-request
     * usage data (Antigravity CLI does not expose token counts in its stream).
     * When false, the live status card shows "Usage not tracked for this route"
     * instead of misleading zero counters. True once at least one real
     * [recordUpstreamResult] call has supplied non-zero token data, or once
     * [markUsageTracked] has explicitly affirmed tracking for the route.
     */
    val usageTracked: Boolean = true,
) {
    val dayTokens: Long get() = dayInputTokens + dayOutputTokens
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens
    val remainingToday: Int?
        get() = if (dailyRequestLimit > 0) (dailyRequestLimit - dayRequests).coerceAtLeast(0) else null
    val limitFraction: Float
        get() = if (dailyRequestLimit > 0) (dayRequests.toFloat() / dailyRequestLimit).coerceIn(0f, 1f) else 0f
}

/** Test seam for provider usage persistence so the tracker is unit-testable. */
interface AppPreferencesBridge {
    fun saveProviderUsage(json: String)
    fun loadProviderUsage(): String?
}

/**
 * Tracks how much each provider is used and whether it is healthy, so the
 * Agent screen can show live status: requests made, tokens spent and how
 * much of a user-configured daily limit is left before the failover chain
 * has to take over.
 *
 * Counters live in memory and are persisted through [AppPreferencesBridge] on
 * every mutation. All methods are thread-safe: upstream results arrive from
 * LocalFormatGateway worker threads while failover events arrive from the
 * runtime event collector.
 */
class ProviderUsageTracker(private val preferences: AppPreferencesBridge) {

    var onChange: (() -> Unit)? = null

    private val lock = Any()
    private val snapshots = LinkedHashMap<ProviderKind, ProviderUsageSnapshot>()
    private var dayKey: String = currentDayKey()

    init {
        synchronized(lock) { load() }
    }

    /** A user task started on this provider (one prompt = one agent task). */
    fun recordTurnStart(kind: ProviderKind) = mutate(kind) { current ->
        current.copy(
            dayTasks = current.dayTasks + 1,
            totalTasks = current.totalTasks + 1,
            state = ProviderLiveState.ACTIVE,
            lastMessage = "Task started",
            lastUpdateAtMillis = now(),
        )
    }

    /**
     * An upstream API call resolved. Successful calls increment request and
     * token counters; failure codes update the live state so the user sees
     * exactly when a provider hits its limit.
     */
    fun recordUpstreamResult(kind: ProviderKind, code: Int, inputTokens: Int, outputTokens: Int) = mutate(kind) { current ->
        // Any real report from a runtime confirms this route is observable.
        val tracked = current.usageTracked || inputTokens > 0 || outputTokens > 0
        when {
            code in 200..299 -> current.copy(
                dayRequests = current.dayRequests + 1,
                totalRequests = current.totalRequests + 1,
                dayInputTokens = current.dayInputTokens + inputTokens.coerceAtLeast(0),
                dayOutputTokens = current.dayOutputTokens + outputTokens.coerceAtLeast(0),
                totalInputTokens = current.totalInputTokens + inputTokens.coerceAtLeast(0),
                totalOutputTokens = current.totalOutputTokens + outputTokens.coerceAtLeast(0),
                state = ProviderLiveState.ACTIVE,
                lastMessage = "OK · ${formatTokens(inputTokens.toLong() + outputTokens)} tokens",
                lastUpdateAtMillis = now(),
                usageTracked = tracked,
            )
            code == 429 || code == 402 -> current.copy(
                state = ProviderLiveState.LIMIT,
                lastMessage = "HTTP $code · limit/quota reached",
                lastUpdateAtMillis = now(),
            )
            code == 401 || code == 403 -> current.copy(
                state = ProviderLiveState.AUTH_ERROR,
                lastMessage = "HTTP $code · key rejected",
                lastUpdateAtMillis = now(),
            )
            code == 408 -> current.copy(
                state = ProviderLiveState.ERROR,
                lastMessage = "HTTP 408 · timeout",
                lastUpdateAtMillis = now(),
            )
            code in 500..599 -> current.copy(
                state = ProviderLiveState.ERROR,
                lastMessage = "HTTP $code · provider error",
                lastUpdateAtMillis = now(),
            )
            else -> current.copy(
                state = if (code == 0) ProviderLiveState.ERROR else current.state,
                lastMessage = "HTTP $code",
                lastUpdateAtMillis = now(),
                usageTracked = tracked,
            )
        }
    }

    /**
     * Mark a provider's usage as not trackable by the current runtime route.
     * Used when the runtime cannot report real per-request usage data (for
     * example, Antigravity's CLI stream does not include token counts). The
     * live status card displays "Usage not tracked for this route" instead
     * of showing misleading zero counters; daily-budget failover is also
     * skipped for untracked providers.
     */
    fun markUsageNotTracked(kind: ProviderKind, reason: String) = mutate(kind) { current ->
        current.copy(
            usageTracked = false,
            lastMessage = reason.take(120).ifBlank { "Usage not tracked for this route" },
            lastUpdateAtMillis = now(),
        )
    }

    /**
     * Mark a provider's usage as observable. Used when a runtime starts that
     * is known to report real usage (Claude Code result event, DSH turn/end,
     * or any route proxied through the in-app gateway).
     */
    fun markUsageTracked(kind: ProviderKind) = mutate(kind) { current ->
        current.copy(usageTracked = true, lastUpdateAtMillis = now())
    }

    /** The runtime reported a session failure; classify it for the live card. */
    fun recordSessionFailure(kind: ProviderKind, reason: String) = mutate(kind) { current ->
        val state = when {
            listOf("429", "rate limit", "quota", "credit", "insufficient", "402", "limit reached").any { reason.lowercase().contains(it) } ->
                ProviderLiveState.LIMIT
            listOf("401", "403", "api key", "authentication", "unauthorized", "invalid").any { reason.lowercase().contains(it) } ->
                ProviderLiveState.AUTH_ERROR
            else -> ProviderLiveState.ERROR
        }
        current.copy(
            state = state,
            lastMessage = reason.replace(Regex("\\s+"), " ").trim().take(120),
            lastUpdateAtMillis = now(),
        )
    }

    /** Failover fired: the source provider is exhausted, the backup is now active. */
    fun recordFailover(fromKind: ProviderKind, toKind: ProviderKind, reason: String) {
        mutate(fromKind) { current ->
            current.copy(
                state = if (current.state == ProviderLiveState.AUTH_ERROR) ProviderLiveState.AUTH_ERROR else ProviderLiveState.LIMIT,
                lastMessage = "Switched to ${toKind.title} · ${reason.replace(Regex("\\s+"), " ").trim().take(80)}",
                lastUpdateAtMillis = now(),
            )
        }
        mutate(toKind) { current ->
            current.copy(
                state = ProviderLiveState.SWITCHED,
                lastMessage = "Took over from ${fromKind.title}",
                lastUpdateAtMillis = now(),
            )
        }
    }

    fun recordSessionCompleted(kind: ProviderKind) = mutate(kind) { current ->
        current.copy(
            state = ProviderLiveState.ACTIVE,
            lastMessage = "Task completed",
            lastUpdateAtMillis = now(),
        )
    }

    fun setDailyLimit(kind: ProviderKind, limit: Int) = mutate(kind) { current ->
        current.copy(
            dailyRequestLimit = limit.coerceIn(0, 100_000),
            lastUpdateAtMillis = now(),
        )
    }

    fun saveRemoteUsage(kind: ProviderKind, json: String) = mutate(kind) { current ->
        current.copy(
            remoteUsageJson = json,
            remoteUsageFetchedAtMillis = now(),
        )
    }

    fun snapshotMap(): Map<ProviderKind, ProviderUsageSnapshot> = synchronized(lock) {
        rolloverIfNeededLocked()
        snapshots.toMap()
    }

    fun snapshot(kind: ProviderKind): ProviderUsageSnapshot = snapshotMap()[kind] ?: ProviderUsageSnapshot(kind)

    private fun mutate(kind: ProviderKind, transform: (ProviderUsageSnapshot) -> ProviderUsageSnapshot) {
        synchronized(lock) {
            rolloverIfNeededLocked()
            snapshots[kind] = transform(snapshots[kind] ?: ProviderUsageSnapshot(kind))
            persistLocked()
        }
        onChange?.invoke()
    }

    private fun rolloverIfNeededLocked() {
        val today = currentDayKey()
        if (today == dayKey) return
        dayKey = today
        for ((kind, value) in snapshots) {
            snapshots[kind] = value.copy(
                dayRequests = 0,
                dayTasks = 0,
                dayInputTokens = 0,
                dayOutputTokens = 0,
            )
        }
    }

    private fun persistLocked() {
        val root = JSONObject()
        root.put("day", dayKey)
        val providers = JSONObject()
        for ((kind, value) in snapshots) {
            providers.put(kind.name, JSONObject().apply {
                put("dayRequests", value.dayRequests)
                put("dayTasks", value.dayTasks)
                put("dayIn", value.dayInputTokens)
                put("dayOut", value.dayOutputTokens)
                put("totalRequests", value.totalRequests)
                put("totalTasks", value.totalTasks)
                put("totalIn", value.totalInputTokens)
                put("totalOut", value.totalOutputTokens)
                put("state", value.state.name)
                put("lastMessage", value.lastMessage)
                put("lastUpdateAt", value.lastUpdateAtMillis)
                put("limit", value.dailyRequestLimit)
                put("remoteJson", value.remoteUsageJson ?: "")
                put("remoteAt", value.remoteUsageFetchedAtMillis)
                put("usageTracked", value.usageTracked)
            })
        }
        root.put("providers", providers)
        preferences.saveProviderUsage(root.toString())
    }

    private fun load() {
        val raw = preferences.loadProviderUsage() ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        dayKey = root.optString("day").ifBlank { currentDayKey() }
        val providers = root.optJSONObject("providers") ?: return
        for (kind in ProviderKind.entries) {
            val item = providers.optJSONObject(kind.name) ?: continue
            snapshots[kind] = ProviderUsageSnapshot(
                kind = kind,
                dayRequests = item.optInt("dayRequests"),
                dayTasks = item.optInt("dayTasks"),
                dayInputTokens = item.optLong("dayIn"),
                dayOutputTokens = item.optLong("dayOut"),
                totalRequests = item.optLong("totalRequests"),
                totalTasks = item.optLong("totalTasks"),
                totalInputTokens = item.optLong("totalIn"),
                totalOutputTokens = item.optLong("totalOut"),
                state = runCatching { ProviderLiveState.valueOf(item.optString("state")) }
                    .getOrDefault(ProviderLiveState.IDLE),
                lastMessage = item.optString("lastMessage"),
                lastUpdateAtMillis = item.optLong("lastUpdateAt"),
                dailyRequestLimit = item.optInt("limit"),
                remoteUsageJson = item.optString("remoteJson").ifBlank { null },
                remoteUsageFetchedAtMillis = item.optLong("remoteAt"),
                usageTracked = item.optBoolean("usageTracked", true),
            )
        }
        rolloverIfNeededLocked()
    }

    companion object {
        private fun now() = System.currentTimeMillis()

        fun currentDayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        fun formatTokens(tokens: Long): String = when {
            tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}
