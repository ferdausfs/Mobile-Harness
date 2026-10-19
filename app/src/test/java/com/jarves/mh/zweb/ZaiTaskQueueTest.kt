package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ZaiTaskQueueTest {

    private fun entry(
        id: String = "e1",
        status: ZaiTaskQueue.Status = ZaiTaskQueue.Status.PENDING,
        enqueuedAt: Long = 1_000L,
    ) = ZaiTaskQueue.Entry(
        id = id,
        projectId = "p1",
        chatId = "c1",
        title = "Fix the bug",
        prompt = "do things",
        continueChat = false,
        status = status,
        enqueuedAtMillis = enqueuedAt,
    )

    @Test
    fun `enqueue appends pending and caps the list`() {
        var entries = emptyList<ZaiTaskQueue.Entry>()
        repeat(ZaiTaskQueue.MAX_ENTRIES + 5) { i ->
            entries = ZaiTaskQueue.enqueue(entries, "p", "c", "t$i", "prompt$i", false, nowMillis = i.toLong())
        }
        assertEquals(ZaiTaskQueue.MAX_ENTRIES, entries.size)
        assertEquals(ZaiTaskQueue.Status.PENDING, entries.last().status)
        assertTrue(entries.last().title.startsWith("t"))
    }

    @Test
    fun `markRunning then markTerminal records lifecycle`() {
        val entries = listOf(entry())
        val running = ZaiTaskQueue.markRunning(entries, "e1", nowMillis = 5_000L)
        assertEquals(ZaiTaskQueue.Status.RUNNING, running[0].status)
        assertEquals(5_000L, running[0].startedAtMillis)
        assertEquals(1, running[0].attempts)
        val done = ZaiTaskQueue.markTerminal(running, "e1", ZaiTaskQueue.Status.DONE, null, 9_000L)
        assertEquals(ZaiTaskQueue.Status.DONE, done[0].status)
        assertEquals(9_000L, done[0].finishedAtMillis)
        assertNull(done[0].lastError)
    }

    @Test
    fun `markTerminal bounds the error text and rejects non-terminal status`() {
        val entries = listOf(entry())
        val failed = ZaiTaskQueue.markTerminal(entries, "e1", ZaiTaskQueue.Status.FAILED, "x".repeat(1_000), 2L)
        assertEquals(ZaiTaskQueue.MAX_ERROR_CHARS, failed[0].lastError!!.length)
        try {
            ZaiTaskQueue.markTerminal(entries, "e1", ZaiTaskQueue.Status.RUNNING, null, 2L)
            org.junit.Assert.fail("non-terminal status must be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `reconcile reports a running entry as killed`() {
        val result = ZaiTaskQueue.reconcile(listOf(entry(status = ZaiTaskQueue.Status.RUNNING)), nowMillis = 10_000L, signedIn = true)
        assertEquals(ZaiTaskQueue.Status.FAILED, result.entries[0].status)
        assertEquals(1, result.reported.size)
        assertTrue(result.entries[0].lastError!!.contains("killed"))
        assertTrue(result.toResume.isEmpty())
    }

    @Test
    fun `reconcile resumes a fresh pending entry when signed in`() {
        val result = ZaiTaskQueue.reconcile(
            listOf(entry(enqueuedAt = 1_000L)),
            nowMillis = 1_000L + ZaiTaskQueue.RESUME_WINDOW_MILLIS,
            signedIn = true,
        )
        assertEquals(1, result.toResume.size)
        assertEquals(0, result.reported.size)
    }

    @Test
    fun `reconcile reports a stale or signed-out pending entry`() {
        val stale = ZaiTaskQueue.reconcile(
            listOf(entry(enqueuedAt = 1_000L)),
            nowMillis = 1_000L + ZaiTaskQueue.RESUME_WINDOW_MILLIS + 1,
            signedIn = true,
        )
        assertEquals(0, stale.toResume.size)
        assertEquals(ZaiTaskQueue.Status.FAILED, stale.entries[0].status)
        assertTrue(stale.entries[0].lastError!!.contains("restarted"))

        val signedOut = ZaiTaskQueue.reconcile(listOf(entry(enqueuedAt = 1_000L)), nowMillis = 2_000L, signedIn = false)
        assertEquals(0, signedOut.toResume.size)
        assertEquals(ZaiTaskQueue.Status.FAILED, signedOut.entries[0].status)
    }

    @Test
    fun `terminal statuses pass through reconcile untouched`() {
        val entries = listOf(
            entry(id = "d", status = ZaiTaskQueue.Status.DONE),
            entry(id = "f", status = ZaiTaskQueue.Status.FAILED),
            entry(id = "x", status = ZaiTaskQueue.Status.CANCELLED),
        )
        val result = ZaiTaskQueue.reconcile(entries, 10L, signedIn = true)
        assertTrue(result.reported.isEmpty())
        assertTrue(result.toResume.isEmpty())
        assertEquals(entries, result.entries)
    }

    @Test
    fun `prune keeps unfinished and recent finished, drops old finished`() {
        val now = 100_000L
        val entries = listOf(
            entry(id = "old-done", status = ZaiTaskQueue.Status.DONE).copy(finishedAtMillis = now - 2 * 24 * 60 * 60_000L),
            entry(id = "new-done", status = ZaiTaskQueue.Status.DONE).copy(finishedAtMillis = now - 60_000L),
            entry(id = "pending", status = ZaiTaskQueue.Status.PENDING),
        )
        val pruned = ZaiTaskQueue.prune(entries, now)
        assertEquals(listOf("new-done", "pending"), pruned.map { it.id })
    }

    @Test
    fun `json round-trips all fields`() {
        val entries = listOf(
            ZaiTaskQueue.markRunning(listOf(entry(id = "a")), "a", 5L)[0],
            ZaiTaskQueue.markTerminal(listOf(entry(id = "b")), "b", ZaiTaskQueue.Status.FAILED, "boom", 7L)[0],
            ZaiTaskQueue.markTerminal(listOf(entry(id = "c")), "c", ZaiTaskQueue.Status.CANCELLED, null, 8L)[0],
        )
        val restored = ZaiTaskQueue.fromJson(ZaiTaskQueue.toJson(entries))
        assertEquals(entries, restored)
    }

    @Test
    fun `json tolerates garbage and blank input`() {
        assertTrue(ZaiTaskQueue.fromJson(null).isEmpty())
        assertTrue(ZaiTaskQueue.fromJson("").isEmpty())
        assertTrue(ZaiTaskQueue.fromJson("not json").isEmpty())
        assertTrue(ZaiTaskQueue.fromJson("""[{"id":"x","prompt":""}]""").isEmpty())
    }
}

class ZaiTaskQueueStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `store round-trips through the file`() {
        val file = File(tmp.root, "queue/zweb/task-queue.json")
        val store = ZaiTaskQueueStore(file)
        val first = ZaiTaskQueue.enqueue(emptyList(), "p", "c", "t", "prompt", true, 1L)[0]
        val entries = ZaiTaskQueue.markRunning(listOf(first), first.id, 2L)
        store.save(entries)
        val loaded = ZaiTaskQueueStore(file).load()
        assertEquals(entries, loaded)
    }

    @Test
    fun `missing file loads empty and save creates parents`() {
        val file = File(tmp.root, "does/not/exist.json")
        val store = ZaiTaskQueueStore(file)
        assertTrue(store.load().isEmpty())
        store.save(listOf(ZaiTaskQueue.Entry("zq-1", "p", "c", "t", "prompt", false, ZaiTaskQueue.Status.PENDING, 1L)))
        assertEquals(1, ZaiTaskQueueStore(file).load().size)
    }
}

class ZaiForegroundPolicyTest {

    @Test
    fun `checkpoint fires only inside the lead window`() {
        val deadline = ZaiForegroundPolicy.checkpointDeadlineMillis
        assertTrue(!ZaiForegroundPolicy.mustCheckpoint(0L))
        assertTrue(!ZaiForegroundPolicy.mustCheckpoint(deadline - 1))
        assertTrue(ZaiForegroundPolicy.mustCheckpoint(deadline))
        assertTrue(ZaiForegroundPolicy.mustCheckpoint(ZaiForegroundPolicy.DATA_SYNC_LIMIT_MILLIS))
        // lead is 10 minutes before the 6h limit
        assertEquals(6 * 60 * 60_000L - 10 * 60_000L, deadline)
    }

    @Test
    fun `notification detail prefers step then phase then fallback`() {
        assertEquals("Searching the web · 45s", ZaiForegroundPolicy.notificationDetail("Thinking", "Searching the web", 45))
        assertEquals("Answering · 1m 5s", ZaiForegroundPolicy.notificationDetail("Answering", null, 65))
        assertEquals("Working on your task · 1h 2m", ZaiForegroundPolicy.notificationDetail(null, null, 3_750))
        assertEquals("Thinking · 0s", ZaiForegroundPolicy.notificationDetail("Thinking", "", 0))
    }

    @Test
    fun `wake lock never exceeds the data sync window`() {
        assertTrue(ZaiForegroundPolicy.WAKE_LOCK_MILLIS <= ZaiForegroundPolicy.DATA_SYNC_LIMIT_MILLIS)
        assertTrue(ZaiForegroundPolicy.checkpointMessage.contains("6-hour"))
    }
}
