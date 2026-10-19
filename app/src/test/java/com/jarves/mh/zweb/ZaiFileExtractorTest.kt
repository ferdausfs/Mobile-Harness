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
    fun `plain content after a marker without a fence is captured (v1_0_34)`() {
        // v1.0.33 behavior: a marker without a fence was unmatched → 0 files
        // (the phone bug). v1.0.34 treats the lines after the marker as the
        // file's plain content — the model may paste files with no fence.
        val reply = "FILE: ghost.txt\nNo block followed."
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("ghost.txt", result.files[0].path)
        assertEquals("No block followed.", result.files[0].content)
        assertTrue(result.unmatchedMarkers.isEmpty())
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

    // ── v1.0.34: the reply text the app ALREADY captured ──
    //
    // The finish-flow text is the rendered DOM's innerText: the site's markdown
    // renderer consumed the fence lines, and the code content survives inside
    // <pre><code> elements that the extraction ships separately. The v1.0.33
    // phone evidence (reply shown in chat with FILE: blocks, sync found 0
    // files) is exactly this shape.

    /** A realistic captured reply for the v1.0.33 phone task (3 files, Bengali prose). */
    private val capturedReplyText = """
        আমি আপনার জন্য তিনটি ফাইল তৈরি করেছি:

        FILE: test/hello.txt
        hello from zai

        FILE: test/index.html
        <!DOCTYPE html>
        <html>
        <head>
        <link rel="stylesheet" href="style.css">
        </head>
        <body>
        <h1>Hello</h1>
        </body>
        </html>

        FILE: test/style.css
        body {
        margin: 0;
        background: #fff;
        }

        Summary: সবগুলো ফাইল সফলভাবে তৈরি হয়েছে — উপরের FILE: ব্লকগুলো দেখুন।
    """.trimIndent()

    /** The code blocks the DOM extraction reports for the same reply (in order). */
    private val capturedReplyBlocks = listOf(
        ZaiFileExtractor.CodeBlock(language = "text", text = "hello from zai"),
        ZaiFileExtractor.CodeBlock(
            language = "html",
            text = "<!DOCTYPE html>\n<html>\n<head>\n<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<h1>Hello</h1>\n</body>\n</html>",
        ),
        ZaiFileExtractor.CodeBlock(language = "css", text = "body {\nmargin: 0;\nbackground: #fff;\n}"),
    )

    @Test
    fun `captured reply without fences resolves through the code blocks`() {
        val result = ZaiFileExtractor.parseCaptured(capturedReplyText, capturedReplyBlocks)
        assertEquals(3, result.files.size)
        assertEquals("test/hello.txt", result.files[0].path)
        assertEquals("hello from zai", result.files[0].content)
        assertEquals("text", result.files[0].language)
        assertEquals("test/index.html", result.files[1].path)
        assertTrue(result.files[1].content.contains("<h1>Hello</h1>"))
        assertEquals("html", result.files[1].language)
        assertEquals("test/style.css", result.files[2].path)
        assertTrue(result.files[2].content.contains("background: #fff;"))
        // The trailing Bengali prose must NOT leak into style.css (block pairing).
        assertTrue(!result.files[2].content.contains("ফাইল"))
        assertTrue(result.unmatchedMarkers.isEmpty())
    }

    @Test
    fun `captured reply without blocks falls back to plain content until the next marker`() {
        val result = ZaiFileExtractor.parseCaptured(capturedReplyText, emptyList())
        assertEquals(3, result.files.size)
        assertEquals("hello from zai", result.files[0].content)
        assertTrue(result.files[1].content.contains("<!DOCTYPE html>"))
        // The protocol's Summary: terminator (and the blank line before it)
        // ends the last file — the trailing prose never leaks in.
        assertTrue(!result.files[2].content.contains("সবগুলো"))
        assertTrue(result.files[2].content.contains("background: #fff;"))
    }

    @Test
    fun `plain content stops at the summary terminator even mid-reply`() {
        val text = """
            FILE: solo.txt
            just one file

            Summary: done

            And some more prose that is NOT file content.
        """.trimIndent()
        val result = ZaiFileExtractor.parseCaptured(text, emptyList())
        assertEquals(1, result.files.size)
        assertEquals("just one file", result.files[0].content)
    }

    @Test
    fun `captured reply pairs blocks even when innerText whitespace differs`() {
        // innerText may render &nbsp; or collapse spaces — the pairing matches
        // whitespace-collapsed lines, so small differences must not matter.
        val text = """
            FILE: notes/a.md
            line   one
            line two

            FILE: notes/b.md
            alpha
            beta
        """.trimIndent()
        val blocks = listOf(
            ZaiFileExtractor.CodeBlock("markdown", "line one\nline two"),
            ZaiFileExtractor.CodeBlock("markdown", "alpha\nbeta"),
        )
        val result = ZaiFileExtractor.parseCaptured(text, blocks)
        assertEquals(2, result.files.size)
        assertEquals("line one\nline two", result.files[0].content)
        assertEquals("alpha\nbeta", result.files[1].content)
    }

    @Test
    fun `fenced replies keep the original grammar under parseCaptured`() {
        val result = ZaiFileExtractor.parseCaptured(replyWithTwoFiles, emptyList())
        assertEquals(2, result.files.size)
        assertEquals("print(\"hello\")\nline2 = 2", result.files[0].content)
        assertEquals("python", result.files[0].language)
        assertTrue(result.unmatchedMarkers.isEmpty())
    }

    @Test
    fun `mixed reply - fenced first file, unfenced second file`() {
        val text = """
            FILE: a.txt
            ```text
            aaa
            ```

            FILE: b.txt
            bbb
        """.trimIndent()
        val result = ZaiFileExtractor.parseCaptured(text, emptyList())
        assertEquals(2, result.files.size)
        assertEquals("aaa", result.files[0].content)
        assertEquals("bbb", result.files[1].content)
        assertTrue(result.unmatchedMarkers.isEmpty())
    }

    @Test
    fun `crlf and language label text are tolerated in captured replies`() {
        val text = "FILE: run.bat\r\n```text\r\necho hi\r\n```\r\n"
        val result = ZaiFileExtractor.parseCaptured(text, emptyList())
        assertEquals(1, result.files.size)
        assertEquals("echo hi", result.files[0].content)
        assertEquals("text", result.files[0].language)
    }

    @Test
    fun `four-backtick fences are supported`() {
        val reply = """
            FILE: md/readme.md
            ````markdown
            # Title with ``` inside
            ````
        """.trimIndent()
        val result = ZaiFileExtractor.parse(reply)
        assertEquals(1, result.files.size)
        assertEquals("# Title with ``` inside", result.files[0].content)
    }

    @Test
    fun `marker with no recoverable content stays honestly unmatched`() {
        val text = """
            কাজ শেষ।

            FILE: missing.txt
        """.trimIndent()
        val result = ZaiFileExtractor.parseCaptured(text, emptyList())
        assertEquals(0, result.files.size)
        assertEquals(listOf("missing.txt"), result.unmatchedMarkers)
    }

    @Test
    fun `unsafe path in a captured reply is rejected not written`() {
        val text = """
            FILE: ../escape.txt
            content here
        """.trimIndent()
        val result = ZaiFileExtractor.parseCaptured(text, emptyList())
        assertEquals(0, result.files.size)
        assertEquals(1, result.invalidPaths.size)
    }

    @Test
    fun `code blocks unrelated to markers never create files`() {
        val result = ZaiFileExtractor.parseCaptured("Just an example:\nfoo\nbar", capturedReplyBlocks)
        assertEquals(0, result.files.size)
    }
}
