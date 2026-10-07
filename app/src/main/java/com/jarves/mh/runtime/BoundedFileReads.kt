package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile

/** Reads at most [maxBytes] from the end of a file without allocating for the whole file. */
internal fun File.readTailText(maxBytes: Int): String {
    if (!isFile || maxBytes <= 0) return ""
    return RandomAccessFile(this, "r").use { input ->
        val byteCount = minOf(input.length(), maxBytes.toLong()).toInt()
        input.seek(input.length() - byteCount)
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
}

/**
 * Turns arbitrary-offset chunk reads from a process output file into complete
 * lines. Chunks are appended at the byte level and only \n-terminated runs are
 * decoded: decoding each chunk on its own (the previous approach) corrupted a
 * multi-byte UTF-8 character that straddled the 16 KB read boundary, which
 * broke the JSON event line the character belonged to and silently dropped it.
 */
internal class Utf8LineAssembler {
    private var pending: ByteArray = ByteArray(0)

    /** Appends [count] bytes of [chunk]; returns every complete line (newline stripped). */
    fun append(chunk: ByteArray, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val fresh = if (count == chunk.size) chunk else chunk.copyOf(count)
        val data = if (pending.isEmpty()) fresh else pending + fresh
        var start = 0
        val lines = ArrayList<String>()
        var newline = data.indexOf('\n'.code.toByte(), start)
        while (newline >= 0) {
            lines.add(data.decodeToString(start, newline).trimEnd('\r'))
            start = newline + 1
            newline = data.indexOf('\n'.code.toByte(), start)
        }
        pending = if (start < data.size) data.copyOfRange(start, data.size) else ByteArray(0)
        return lines
    }

    /** Decodes the trailing bytes after the stream ended (a last line with no newline). */
    fun drain(): String? {
        if (pending.isEmpty()) return null
        val text = pending.decodeToString(0, pending.size).trimEnd('\r')
        pending = ByteArray(0)
        return text
    }

    private fun ByteArray.indexOf(byte: Byte, from: Int): Int {
        for (index in maxOf(from, 0) until size) {
            if (this[index] == byte) return index
        }
        return -1
    }
}
