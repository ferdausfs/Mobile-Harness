package com.jarves.mh.model

import com.jarves.mh.data.ProviderLiveState
import com.jarves.mh.data.ProviderUsageTracker
import com.jarves.mh.model.ProviderKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the live provider usage tracker: counters, day rollover,
 * failure classification and the daily-limit math that drives the status card.
 */
class ProviderUsageTrackerTest {

    private class FakePrefs {
        var json: String? = null
    }

    private fun trackerWith(prefs: FakePrefs): ProviderUsageTracker {
        val preferences = object : com.jarves.mh.data.AppPreferencesBridge {
            override fun saveProviderUsage(json: String) { prefs.json = json }
            override fun loadProviderUsage(): String? = prefs.json
        }
        return ProviderUsageTracker(preferences)
    }

    @Test
    fun `turn start counts tasks and sets active`() {
        val prefs = FakePrefs()
        val tracker = trackerWith(prefs)
        tracker.recordTurnStart(ProviderKind.OLLAMA_CLOUD)
        val snapshot = tracker.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertEquals(1, snapshot.dayTasks)
        assertEquals(1, snapshot.totalTasks)
        assertEquals(ProviderLiveState.ACTIVE, snapshot.state)
    }

    @Test
    fun `successful upstream results accumulate requests and tokens`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 120, 80)
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 30, 20)
        val snapshot = tracker.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertEquals(2, snapshot.dayRequests)
        assertEquals(150, snapshot.dayInputTokens)
        assertEquals(100, snapshot.dayOutputTokens)
        assertEquals(250, snapshot.dayTokens)
        assertEquals(ProviderLiveState.ACTIVE, snapshot.state)
    }

    @Test
    fun `limit and auth codes map to distinct states`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 429, 0, 0)
        assertEquals(ProviderLiveState.LIMIT, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 401, 0, 0)
        assertEquals(ProviderLiveState.AUTH_ERROR, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 503, 0, 0)
        assertEquals(ProviderLiveState.ERROR, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
    }

    @Test
    fun `session failure reasons are classified`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordSessionFailure(ProviderKind.OLLAMA_CLOUD, "HTTP 429 rate limit exceeded")
        assertEquals(ProviderLiveState.LIMIT, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
        tracker.recordSessionFailure(ProviderKind.OLLAMA_CLOUD, "invalid api key")
        assertEquals(ProviderLiveState.AUTH_ERROR, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
        tracker.recordSessionFailure(ProviderKind.OLLAMA_CLOUD, "runtime crashed while compiling")
        assertEquals(ProviderLiveState.ERROR, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
    }

    @Test
    fun `failover marks source exhausted and backup took over`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordFailover(ProviderKind.OLLAMA_CLOUD, ProviderKind.DEEPSEEK, "HTTP 429")
        assertEquals(ProviderLiveState.LIMIT, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
        assertEquals(ProviderLiveState.SWITCHED, tracker.snapshot(ProviderKind.DEEPSEEK).state)
        assertTrue(tracker.snapshot(ProviderKind.OLLAMA_CLOUD).lastMessage.contains("DeepSeek"))
    }

    @Test
    fun `daily limit drives remaining math`() {
        val tracker = trackerWith(FakePrefs())
        tracker.setDailyLimit(ProviderKind.OLLAMA_CLOUD, 5)
        repeat(3) { tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 10, 10) }
        val snapshot = tracker.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertEquals(5, snapshot.dailyRequestLimit)
        assertEquals(2, snapshot.remainingToday)
        assertEquals(0.6f, snapshot.limitFraction, 0.001f)
    }

    @Test
    fun `limit unset means no remaining estimate`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 10, 10)
        assertNull(tracker.snapshot(ProviderKind.OLLAMA_CLOUD).remainingToday)
    }

    @Test
    fun `counters persist and reload across instances`() {
        val prefs = FakePrefs()
        val first = trackerWith(prefs)
        first.recordTurnStart(ProviderKind.OLLAMA_CLOUD)
        first.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 100, 40)
        first.setDailyLimit(ProviderKind.OLLAMA_CLOUD, 50)
        assertTrue(prefs.json != null)
        assertTrue(JSONObject(prefs.json!!).has("providers"))

        val second = trackerWith(prefs)
        val snapshot = second.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertEquals(1, snapshot.dayTasks)
        assertEquals(1, snapshot.dayRequests)
        assertEquals(140, snapshot.dayTokens)
        assertEquals(50, snapshot.dailyRequestLimit)
    }

    @Test
    fun `stored day key rollover resets day counters but keeps totals`() {
        val prefs = FakePrefs()
        val first = trackerWith(prefs)
        first.recordUpstreamResult(ProviderKind.OLLAMA_CLOUD, 200, 100, 100)
        val stored = JSONObject(prefs.json!!)
        stored.put("day", "2000-01-01")
        prefs.json = stored.toString()

        val second = trackerWith(prefs)
        val snapshot = second.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertEquals(0, snapshot.dayRequests)
        assertEquals(0, snapshot.dayTokens)
        assertEquals(200, snapshot.totalTokens)
    }

    @Test
    fun `remote usage payload is retained`() {
        val tracker = trackerWith(FakePrefs())
        tracker.saveRemoteUsage(ProviderKind.OLLAMA_CLOUD, """{"usage":{"credits":5}}""")
        val snapshot = tracker.snapshot(ProviderKind.OLLAMA_CLOUD)
        assertTrue(snapshot.remoteUsageJson!!.contains("credits"))
        assertTrue(snapshot.remoteUsageFetchedAtMillis > 0)
    }

    @Test
    fun `rate limit failure wording maps to limit state`() {
        // Regression: the runtime detectors now emit this exact wording for 429;
        // it must land on LIMIT (yellow), not AUTH_ERROR (red).
        val tracker = trackerWith(FakePrefs())
        tracker.recordSessionFailure(ProviderKind.OLLAMA_CLOUD, "The provider is rate limiting requests.")
        assertEquals(ProviderLiveState.LIMIT, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
    }

    @Test
    fun `credits failure wording maps to limit state`() {
        val tracker = trackerWith(FakePrefs())
        tracker.recordSessionFailure(ProviderKind.OLLAMA_CLOUD, "The provider reports insufficient credits or quota.")
        assertEquals(ProviderLiveState.LIMIT, tracker.snapshot(ProviderKind.OLLAMA_CLOUD).state)
    }
}
