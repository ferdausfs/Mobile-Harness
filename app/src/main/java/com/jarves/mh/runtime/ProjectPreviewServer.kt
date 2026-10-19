package com.jarves.mh.runtime

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * v1.0.34 (CONCERN B): serves a project workspace folder over localhost so the
 * Preview tab works for EVERY project — the files may have arrived from
 * Antigravity, the Z.ai file sync, or the user; the server only knows the
 * folder. The Preview tab already renders [url]; this class fills the gap that
 * Z.ai projects had no way to get a previewUrl at all.
 *
 * Safety model:
 *  - binds 127.0.0.1 ONLY (never the LAN);
 *  - request paths are sanitized (no `..`, no absolute escapes, no symlink
 *    escape — canonical path must stay under the root);
 *  - only GET/HEAD; directories resolve to index.html;
 *  - binaries stream in bounded chunks; a hard per-response cap keeps a huge
 *    file from exhausting memory.
 *
 * The request logic ([resolveRequestedFile], [contentTypeFor], response codes)
 * is pure over [File] so the JVM can test routing/safety without sockets.
 */
class ProjectPreviewServer(private val root: File) {

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /** The localhost URL the Preview tab should open; null = not running. */
    val url: String?
        get() = server?.takeIf { !it.isClosed }?.let { "http://127.0.0.1:${it.localPort}/" }

    @Synchronized
    fun start(): Boolean {
        if (running) return url != null
        val socket = try {
            ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            return false
        }
        server = socket
        running = true
        acceptThread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // closed by stop()
                }
                if (!running) {
                    runCatching { client.close() }
                    break
                }
                handleClient(client)
            }
        }.apply {
            name = "mh-preview-server"
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        // One short-lived thread per request: preview traffic is a phone
        // browser reading static files; a tiny pool would be over-engineering.
        Thread {
            try {
                client.use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream().bufferedReader()
                    val requestLine = input.readLine() ?: return@use
                    // Drain the header block (we do not need the fields).
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty() || line == "\r") break
                    }
                    val parts = requestLine.trim().split(" ")
                    if (parts.size < 2) {
                        respond(socket, code = "400 Bad Request", body = ByteArray(0), contentType = null, headOnly = true)
                        return@use
                    }
                    val method = parts[0].uppercase()
                    if (method != "GET" && method != "HEAD") {
                        respond(socket, code = "405 Method Not Allowed", body = ByteArray(0), contentType = null, headOnly = true)
                        return@use
                    }
                    val rawPath = parts[1].substringBefore('?')
                    val file = resolveRequestedFile(root, rawPath)
                    if (file == null) {
                        respond(socket, code = "404 Not Found", body = NOT_FOUND_BODY.toByteArray(), contentType = "text/plain; charset=utf-8", headOnly = method == "HEAD")
                        return@use
                    }
                    val bytes = if (file.length() > MAX_FILE_BYTES) {
                        file.inputStream().use { stream -> readBounded(stream, MAX_FILE_BYTES) }
                    } else {
                        file.readBytes()
                    }
                    respond(
                        socket,
                        code = "200 OK",
                        body = bytes,
                        contentType = contentTypeFor(file.name),
                        headOnly = method == "HEAD",
                    )
                }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }.apply { name = "mh-preview-req"; isDaemon = true; start() }
    }

    /** minSdk-28-safe bounded read (InputStream.readNBytes(int) is API 33+). */
    private fun readBounded(stream: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 1 shl 20))
        val buf = ByteArray(64 * 1024)
        var remaining = max
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    private fun respond(socket: Socket, code: String, body: ByteArray, contentType: String?, headOnly: Boolean) {
        val headers = buildString {
            append("HTTP/1.1 ").append(code).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().let { out ->
            out.write(headers.toByteArray())
            if (!headOnly && body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    companion object {

        /** Per-response cap: previews are for web-sized assets, not archives. */
        const val MAX_FILE_BYTES = 25 * 1024 * 1024

        private const val NOT_FOUND_BODY = "not found"

        /**
         * Maps a request path to a file under [root], or null when the request
         * escapes the root or names nothing. Query strings are stripped, "/",
         * "" and directory paths resolve to index.html when present. Rejects
         * traversal, null bytes, and any canonical path outside [root] (symlink
         * escape included).
         */
        fun resolveRequestedFile(root: File, rawPath: String): File? {
            if (!root.isDirectory) return null
            val noQuery = rawPath.substringBefore('?')
            val decoded = try {
                URLDecoder.decode(noQuery, "UTF-8")
            } catch (e: Exception) {
                return null
            }
            if (decoded.contains('\u0000')) return null
            val cleaned = decoded.trim().trimStart('/')
            if (cleaned.isEmpty()) {
                val index = File(root, "index.html")
                return index.takeIf { it.isFile }
            }
            if (cleaned.endsWith('/')) {
                val index = File(root, cleaned + "index.html")
                return contained(root, index)?.takeIf { it.isFile }
            }
            val candidate = File(root, cleaned)
            val resolved = contained(root, candidate) ?: return null
            if (resolved.isDirectory) {
                val index = File(resolved, "index.html")
                return index.takeIf { it.isFile }
            }
            return resolved.takeIf { it.isFile }
        }

        /** Canonical containment check: the ONLY path-safety authority here. */
        private fun contained(root: File, candidate: File): File? {
            val rootPath = root.canonicalFile.toPath()
            val candidatePath = candidate.toPath()
            val canonical = runCatching { candidate.toPath().toRealPath() }.getOrElse { candidatePath }
            return if (canonical.startsWith(rootPath) &&
                (candidatePath.startsWith(rootPath) || canonical == candidatePath.normalize())
            ) {
                canonical.toFile()
            } else {
                null
            }
        }

        /** Content types for the file types a static web preview actually serves. */
        fun contentTypeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "js", "mjs" -> "text/javascript; charset=utf-8"
                "json" -> "application/json; charset=utf-8"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "ico" -> "image/x-icon"
                "txt", "md" -> "text/plain; charset=utf-8"
                "xml" -> "application/xml"
                "pdf" -> "application/pdf"
                "wasm" -> "application/wasm"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }
        }
    }
}
