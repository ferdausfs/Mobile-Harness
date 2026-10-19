package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.34 CONCERN B: the local preview server's routing/safety rules. Pure
 * over the filesystem (TemporaryFolder), so no sockets are needed — the socket
 * layer only wraps these decisions.
 */
class ProjectPreviewServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `root path resolves to index html`() {
        val site = tmp.newFolder("site")
        tmp.newFile("site/index.html").writeText("<h1>hi</h1>")
        val resolved = ProjectPreviewServer.resolveRequestedFile(site, "/")
        assertNotNull(resolved)
        assertEquals("index.html", resolved!!.name)
    }

    @Test
    fun `nested files resolve and directories fall back to their index`() {
        val root = tmp.newFolder("site")
        tmp.newFile("site/style.css").writeText("body{}")
        tmp.newFolder("site/sub")
        tmp.newFile("site/sub/index.html").writeText("sub")
        assertEquals("style.css", ProjectPreviewServer.resolveRequestedFile(root, "/style.css")!!.name)
        assertEquals("index.html", ProjectPreviewServer.resolveRequestedFile(root, "/sub/")!!.name)
        assertEquals("index.html", ProjectPreviewServer.resolveRequestedFile(root, "/sub")!!.name)
    }

    @Test
    fun `traversal and null bytes are rejected`() {
        val root = tmp.newFolder("site")
        tmp.newFile("site/index.html").writeText("x")
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/../secret.txt"))
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/..%2Fsecret.txt"))
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/a/\u0000b"))
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/%2e%2e/secret.txt"))
    }

    @Test
    fun `missing files are 404 (null)`() {
        val root = tmp.newFolder("site")
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/nope.html"))
        // No index.html at the root either.
        assertNull(ProjectPreviewServer.resolveRequestedFile(root, "/"))
    }

    @Test
    fun `symlink escape stays inside the root`() {
        val root = tmp.newFolder("site")
        val outside = tmp.newFolder("outside")
        val secret = java.io.File(outside, "secret.txt")
        secret.writeText("s3cret")
        val link = java.io.File(root, "link.txt")
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                secret.toPath(),
            )
        }.isSuccess
        if (made) {
            assertNull("a symlink pointing outside the root must be refused", ProjectPreviewServer.resolveRequestedFile(root, "/link.txt"))
        }
    }

    @Test
    fun `query strings are not part of the path`() {
        val root = tmp.newFolder("site")
        tmp.newFile("site/app.js").writeText("console.log(1)")
        assertEquals("app.js", ProjectPreviewServer.resolveRequestedFile(root, "/app.js?v=2")!!.name)
    }

    @Test
    fun `content types cover the web basics`() {
        assertEquals("text/html; charset=utf-8", ProjectPreviewServer.contentTypeFor("index.html"))
        assertEquals("text/css; charset=utf-8", ProjectPreviewServer.contentTypeFor("style.CSS"))
        assertEquals("text/javascript; charset=utf-8", ProjectPreviewServer.contentTypeFor("app.js"))
        assertEquals("application/json; charset=utf-8", ProjectPreviewServer.contentTypeFor("data.json"))
        assertEquals("image/png", ProjectPreviewServer.contentTypeFor("logo.png"))
        assertEquals("image/svg+xml", ProjectPreviewServer.contentTypeFor("icon.svg"))
        assertEquals("application/octet-stream", ProjectPreviewServer.contentTypeFor("bundle.bin"))
    }

    @Test
    fun `server binds localhost only and reports its url`() {
        val server = ProjectPreviewServer(tmp.newFolder("site"))
        try {
            assertTrue(server.start())
            val url = server.url
            assertNotNull(url)
            assertTrue(url!!.startsWith("http://127.0.0.1:"))
        } finally {
            server.stop()
        }
        assertNull(server.url)
    }

    @Test
    fun `server serves files over http`() {
        val root = tmp.newFolder("site")
        tmp.newFile("site/index.html").writeText("<h1>hello preview</h1>")
        val server = ProjectPreviewServer(root)
        assertTrue(server.start())
        try {
            val url = server.url!!
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.contains("hello preview"))
            assertEquals("text/html; charset=utf-8", connection.contentType)
        } finally {
            server.stop()
        }
    }
}
