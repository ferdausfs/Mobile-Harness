package com.jarves.mh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResumePolicyTest {

    @Test
    fun `206 resumes at the offset and totals resumeFrom plus remaining length`() {
        val decision = DownloadResumePolicy.decide(
            httpCode = 206,
            resumeFrom = 12_345L,
            contentLength = 87_945_594L, // remaining bytes
            manifestSizeBytes = 87_957_939L,
        )
        assertEquals(DownloadResumePolicy.Action.RESUME, decision.action)
        assertEquals(12_345L, decision.offset)
        assertEquals(87_957_939L, decision.total)
        assertNull(decision.failure)
    }

    @Test
    fun `206 without content length falls back to the manifest size`() {
        val decision = DownloadResumePolicy.decide(
            httpCode = 206,
            resumeFrom = 1_000L,
            contentLength = -1L,
            manifestSizeBytes = 50_000L,
        )
        assertEquals(DownloadResumePolicy.Action.RESUME, decision.action)
        assertEquals(1_000L, decision.offset)
        assertEquals(50_000L, decision.total)
    }

    @Test
    fun `2xx restarts from zero whether or not a resume was requested`() {
        val decision = DownloadResumePolicy.decide(
            httpCode = 200,
            resumeFrom = 9_999L,
            contentLength = 87_957_939L,
            manifestSizeBytes = -1L,
        )
        assertEquals(DownloadResumePolicy.Action.RESTART, decision.action)
        assertEquals(0L, decision.offset)
        assertEquals(87_957_939L, decision.total)

        val noLength = DownloadResumePolicy.decide(
            httpCode = 200,
            resumeFrom = 9_999L,
            contentLength = -1L,
            manifestSizeBytes = 12_345L,
        )
        assertEquals(DownloadResumePolicy.Action.RESTART, noLength.action)
        assertEquals(12_345L, noLength.total)
    }

    @Test
    fun `416 drops the unusable partial and asks for a fresh attempt`() {
        val decision = DownloadResumePolicy.decide(
            httpCode = 416,
            resumeFrom = 87_957_939L + 1, // impossible offset
            contentLength = -1L,
            manifestSizeBytes = 87_957_939L,
        )
        assertEquals(DownloadResumePolicy.Action.RETRY_FRESH, decision.action)
        assertEquals(0L, decision.offset)
    }

    @Test
    fun `permanent http failures do not retry`() {
        for (code in listOf(403, 404, 500, 502)) {
            val decision = DownloadResumePolicy.decide(
                httpCode = code,
                resumeFrom = 500L,
                contentLength = -1L,
                manifestSizeBytes = 87_957_939L,
            )
            assertEquals(DownloadResumePolicy.Action.FAIL, decision.action)
            assertEquals("Update download failed (HTTP $code)", decision.failure)
        }
    }

    @Test
    fun `backoff grows linearly and is capped`() {
        assertEquals(1_000L, DownloadResumePolicy.backoffMillis(1))
        assertEquals(2_000L, DownloadResumePolicy.backoffMillis(2))
        assertEquals(3_000L, DownloadResumePolicy.backoffMillis(3))
        assertEquals(4_000L, DownloadResumePolicy.backoffMillis(4))
        assertEquals(4_000L, DownloadResumePolicy.backoffMillis(9))
        assertEquals(1_000L, DownloadResumePolicy.backoffMillis(0))
    }

    @Test
    fun `retry budget is bounded`() {
        assertEquals(5, DownloadResumePolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `partial file names are scoped per version code`() {
        assertEquals("mobile-harness-online-25.apk.part", partFileName("online", 25L))
        assertEquals(false, partFileName("online", 25L) == partFileName("online", 24L))
    }
}
