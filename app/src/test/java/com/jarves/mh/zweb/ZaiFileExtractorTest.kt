package com.jarves.mh.zweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiFileExtractorTest {

    private val replyWithTwoFiles = """
        Here is the plan.

        FILE: src/main.py
        ```python
        print("hello")
        line2 = 2
        ```

        FILE: web/index.html
        ```html
        <h1>Hi</h1>
        ```

        Summary: created two files.
    """.trimIndent()

    @Test
    fun `extracts two files with languages`() {
        val result = ZaiFileExtractor.parse(replyWithTwoFiles)
        assertEquals(2, result.files.size)
        assertEquals("src/main.py", result.files[0].path)
        assertEquals("python", result.files[0].language)
        assertEquals("print(\"hello\")\nline2 = 2", result.files[0].content)
        assertEquals("web/index.html", result.files[1].path)
        assertEquals("html", result.files[1].language)
        assertEquals("<h1>Hi</h1>", result.files[1].content)
        assertTrue(result.unmatchedMarkers.isEmpty())
        assertTrue(result.invalidPaths.isEmpty())
    }

    @Test
    fun `marker is case-insensitive and tolerates bold and backtick wrappers`() {
        val reply = """
            **FILE:** `src/App.kt`
            ```kotlin
            fun main() {}
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("src/App.kt", result.files[0].path)
        assertEquals("kotlin", result.files[0].language)
    }

    @Test
    fun `marker followed by prose sentence still binds to next fence`() {
        val reply = """
            FILE: notes/readme.md

            Sure, here is the file:

            ```markdown
            # Hello
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("notes/readme.md", result.files[0].path)
        assertEquals("# Hello", result.files[0].content)
    }

    @Test
    fun `FILE inside a fence is not a marker`() {
        val reply = """
            FILE: real.txt
            ```
            FILE: fake.txt
            inside block
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("real.txt", result.files[0].path)
        assertEquals("FILE: fake.txt\ninside block", result.files[0].content)
    }

    @Test
    fun `code block without marker is ignored`() {
        val reply = """
            ```js
            console.log(1);
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(0, result.files.size)
    }

    @Test
    fun `unterminated fence still captures content`() {
        val reply = "FILE: cut/off.txt\n```\npartial content\nsecond line"
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("cut/off.txt", result.files[0].path)
        assertEquals("partial content\nsecond line", result.files[0].content)
    }

    @Test
    fun `marker without fence is reported unmatched`() {
        val reply = "FILE: ghost.txt\nNo block followed."
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(0, result.files.size)
        assertEquals(listOf("ghost.txt"), result.unmatchedMarkers)
    }

    @Test
    fun `traversal paths are rejected and leading slashes are stripped`() {
        val reply = """
            FILE: ../evil.txt
            ```
            x
            ```

            FILE: /abs/path.txt
            ```
            y
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        // Absolute-style marker is written repo-rooted under the output dir.
        assertEquals("abs/path.txt", result.files[0].path)
        assertEquals(listOf("../evil.txt"), result.invalidPaths)
    }

    @Test
    fun `windows path separators normalize to forward slashes`() {
        val reply = """
            FILE: src\utils\helper.py
            ```
            ok
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("src/utils/helper.py", result.files[0].path)
    }

    @Test
    fun `duplicate path keeps first block`() {
        val reply = """
            FILE: a.txt
            ```
            first
            ```

            FILE: a.txt
            ```
            second
            ```
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("first", result.files[0].content)
    }

    @Test
    fun `crlf line endings parse cleanly`() {
        val reply = "FILE: b.txt\r\n```\r\ncontent\r\n```\r\n"
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("b.txt", result.files[0].path)
        assertEquals("content", result.files[0].content)
    }

    @Test
    fun `tilde fences are supported`() {
        val reply = """
            FILE: c.txt
            ~~~
            tilde content
            ~~~
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("tilde content", result.files[0].content)
    }

    @Test
    fun `empty reply yields no files`() {
        val result = ZaiFileExtractor.parse("")
        assertEquals(0, result.files.size)
        assertTrue(!result.hasFiles)
    }

    @Test
    fun `path sanitization edge cases`() {
        assertNull(ZaiFileExtractor.sanitizeZaiPath(""))
        assertNull(ZaiFileExtractor.sanitizeZaiPath(".."))
        assertNull(ZaiFileExtractor.sanitizeZaiPath("a/../b"))
        assertNull(ZaiFileExtractor.sanitizeZaiPath("C:\\evil"))
        assertNull(ZaiFileExtractor.sanitizeZaiPath("bad\u0000name"))
        assertEquals("ok.txt", ZaiFileExtractor.sanitizeZaiPath("./ok.txt"))
        assertEquals("a/b/c.txt", ZaiFileExtractor.sanitizeZaiPath("/a//b/c.txt"))
    }
}
