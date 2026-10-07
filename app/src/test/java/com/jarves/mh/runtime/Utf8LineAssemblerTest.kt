package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8LineAssemblerTest {
    @Test
    fun multiByteCharacterSplitAcrossChunksSurvives() {
        val assembler = Utf8LineAssembler()
        val payload = "hello \uD83D\uDE00 world \u4F60\u597D done\n".toByteArray(Charsets.UTF_8)
        // Byte 8 lands inside the 4-byte emoji sequence (bytes 6..9).
        val split = 8
        val first = assembler.append(payload, split)
        assertEquals(emptyList<String>(), first)
        // Callers hand the assembler a fresh buffer per read (the bridge loop
        // fills a new array from the file offset), so pass the tail slice.
        val second = assembler.append(payload.copyOfRange(split, payload.size), payload.size - split)
        assertEquals(listOf("hello \uD83D\uDE00 world \u4F60\u597D done"), second)
        assertNull(assembler.drain())
    }

    @Test
    fun severalLinesInOneChunkAreAllReturned() {
        val assembler = Utf8LineAssembler()
        val payload = "a\nbb\nccc\n".toByteArray(Charsets.UTF_8)
        val lines = assembler.append(payload, payload.size)
        assertEquals(listOf("a", "bb", "ccc"), lines)
        assertNull(assembler.drain())
    }

    @Test
    fun carriageReturnsAreStripped() {
        val assembler = Utf8LineAssembler()
        val payload = "windows\r\nline\r\n".toByteArray(Charsets.UTF_8)
        val lines = assembler.append(payload, payload.size)
        assertEquals(listOf("windows", "line"), lines)
    }

    @Test
    fun trailingFragmentIsDrainedAtEndOfStream() {
        val assembler = Utf8LineAssembler()
        val payload = "complete\npar".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("complete"), assembler.append(payload, payload.size))
        assertEquals("par", assembler.drain())
        assertNull(assembler.drain())
    }

    @Test
    fun emptyAndShortChunksAreIgnored() {
        val assembler = Utf8LineAssembler()
        assertEquals(emptyList<String>(), assembler.append(ByteArray(10), 0))
        val lines = assembler.append("x\n".toByteArray(Charsets.UTF_8), 2)
        assertEquals(listOf("x"), lines)
    }
}
