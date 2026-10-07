package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    private val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val outputPump: Thread? = null,
) : Process() {
    @Volatile private var result: Int? = null

    /**
     * Single-reaper guard. waitFor() and exitValue() (and therefore isAlive(),
     * which watchdog and stop threads call concurrently with a worker blocked
     * in waitFor()) all waitpid the same pid; without this, a concurrent
     * isAlive() can reap the child first, the blocking waitpid then fails with
     * ECHILD, and the error encoding (-138) got cached as the exit code —
     * turning a successful task into a bogus failure. Reaping is serialized
     * here and a known exit status is never overwritten.
     */
    private val reapLock = Any()

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        var transientRetries = 0
        while (true) {
            val cached = synchronized(reapLock) { result }
            cached?.let { return it }
            // Poll instead of blocking inside the lock so watchdog threads
            // can still observe liveness (exitValue) while we wait.
            val status = synchronized(reapLock) { NativeSpawn.waitFor(pid, true) }
            if (status == NativeSpawn.STILL_RUNNING) {
                Thread.sleep(50)
                continue
            }
            if (status < 0 && transientRetries < 3) {
                // errno encoding (e.g. EINTR); transient — retry briefly.
                transientRetries++
                Thread.sleep(20)
                continue
            }
            synchronized(reapLock) {
                // Never cache a negative encoding as the final exit status.
                if (result == null && status >= 0) result = status
            }
            outputPump?.join(1_000)
            return status
        }
    }

    override fun exitValue(): Int {
        synchronized(reapLock) {
            result?.let { return it }
            val status = NativeSpawn.waitFor(pid, true)
            if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
            if (status < 0) return status
            result = status
            return status
        }
    }

    override fun destroy() {
        NativeSpawn.kill(pid, 15)
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        NativeSpawn.kill(pid, 9)
        return this
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal) outputFile.delete()
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned.size == 3 && spawned[0] > 0) { "Native runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val pump = spawned[2].takeIf { it >= 0 }?.let { outputFd ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(outputFd)).use { source ->
                            FileOutputStream(outputFile, false).use { destination -> source.copyTo(destination) }
                        }
                    }
                }, "pocket-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(spawned[0], outputFile, input, pump)
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        System.loadLibrary("pocketspawn")
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
}
