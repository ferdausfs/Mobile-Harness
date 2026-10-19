package com.jarves.mh.zweb

import android.content.Context
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.tokens.SecretRedactor
import com.jarves.mh.tokens.TokenVault
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Executes RUN-loop commands inside the private Linux runtime (Phase 4).
 *
 * Hard rules implemented here:
 *  - the denylist runs FIRST on the model's raw command; a refusal is never
 *    executed and its "refused: <reason>" text becomes the command output so
 *    the model can adapt;
 *  - token VALUES reach the process ONLY as environment variables (names in
 *    argv, values in env) — never in .git/config, remote URLs, shell history
 *    or files (git uses the inline credential helper from ZaiRunCredentials);
 *  - command OUTPUT is redacted (stored values + token-shaped patterns) and
 *    bounded (tail kept) before anything is sent back to chat.z.ai;
 *  - every command runs with a per-command timeout; a timed-out process is
 *    destroyed and reported as such;
 *  - no output file, log or transcript ever receives a token value.
 *
 * The executor is deliberately dumb about the loop — budgets, iteration caps
 * and termination live in [ZaiRunLoopPolicy]; this class only runs one
 * command safely. Crash-safety: nothing here touches startup; every call is
 * self-contained and the ViewModel wraps the whole loop in a guarded scope.
 */
class ZaiRunExecutor(
    private val context: Context,
    private val installer: RuntimeInstaller,
    private val tokenVault: TokenVault,
) {

    data class RunOutcome(
        val command: String,
        /** Null when refused (never executed) or the runtime is unavailable. */
        val exitCode: Int?,
        /** Redacted + bounded process output (or the refusal / unavailable reason). */
        val output: String,
        val refused: Boolean,
        val timedOut: Boolean,
        val durationMillis: Long,
    ) {
        /** One block for the results message sent back to the model. */
        fun asReportBlock(): String = buildString {
            append("$ ")
            append(command)
            append('\n')
            if (refused) {
                append(output)
            } else {
                append("exit: ").append(exitCode ?: -1)
                if (timedOut) append(" (timed out, process killed)")
                if (output.isNotBlank()) {
                    append("\n--- output (redacted, tail) ---\n")
                    append(output)
                }
            }
        }
    }

    /** Env vars injected for a task (names only — used by the echo/refusal rules). */
    fun tokenEnvVarNames(projectId: String?): Set<String> =
        tokenVault.valuesForProject(projectId).keys

    /**
     * Runs one command. [hostWorkspace] is the project workspace directory on
     * the host; [guestWorkspacePath] its path inside the guest (/workspace…).
     * [projectId] selects the enabled tokens ([TokenVault.valuesForProject]).
     * [timeoutMillis] comes from [ZaiRunLoopPolicy]; [extraEnv] carries the
     * token values (never logged).
     */
    suspend fun run(
        command: String,
        hostWorkspace: File,
        guestWorkspacePath: String,
        projectId: String?,
        timeoutMillis: Long = ZaiRunLoopPolicy.DEFAULT_PER_COMMAND_TIMEOUT_MILLIS,
    ): RunOutcome {
        val startedAt = System.currentTimeMillis()
        // 1. The hard rails — refusal text goes back to the model verbatim.
        val tokenNames = tokenEnvVarNames(projectId)
        val decision = ZaiRunDenylist.evaluate(command, guestWorkspacePath, tokenNames)
        if (decision.refused) {
            return RunOutcome(
                command = command,
                exitCode = null,
                output = "refused: ${decision.reason}",
                refused = true,
                timedOut = false,
                durationMillis = 0L,
            )
        }
        // 2. The runtime must exist; otherwise say so instead of hanging.
        val runtime = withContext(Dispatchers.IO) {
            runCatching { installer.installedRuntime() }.getOrNull()
        }
        if (runtime == null) {
            return RunOutcome(
                command = command,
                exitCode = null,
                output = "refused: the private Linux runtime is not installed on this device, " +
                    "so commands cannot run. Continue with a text answer or produce DONE: if the task is complete.",
                refused = true,
                timedOut = false,
                durationMillis = 0L,
            )
        }
        // 3. Environment: token values ONLY as env vars + git prompt guards.
        val tokens = tokenVault.valuesForProject(projectId)
        val env = buildMap {
            putAll(ZaiRunCredentials.gitNetworkEnv())
            putAll(tokens)
        }
        // 4. Git network commands get the inline credential helper (process-
        //    scoped -c flags; nothing persists to .git/config or remote URLs).
        val finalCommand = assembleCommand(command, tokens.keys)
        // 5. Execute under the per-command timeout.
        return withContext(Dispatchers.IO) {
            val outputFile = File(context.cacheDir, "zai-run-${System.nanoTime()}.log")
            var process: java.lang.Process? = null
            try {
                process = installer.process(
                    proot = runtime.proot,
                    rootfs = runtime.rootfs,
                    workspace = hostWorkspace,
                    environment = env,
                    guestCommand = listOf("/usr/bin/bash", "-c", finalCommand),
                    guestWorkspacePath = guestWorkspacePath,
                    outputFile = outputFile,
                )
                var timedOut = false
                val finished = runCatching { process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
                if (!finished) {
                    timedOut = true
                    runCatching { process.destroy() }
                    delay(500)
                    runCatching { process.destroyForcibly() }
                }
                val raw = runCatching {
                    if (outputFile.isFile) outputFile.readText() else ""
                }.getOrDefault("")
                val redacted = SecretRedactor.redact(raw, tokens.values)
                val bounded = boundTail(redacted, ZaiRunLoopPolicy.MAX_OUTPUT_TO_MODEL_CHARS)
                val exit = if (timedOut) null else runCatching { process.exitValue() }.getOrNull()
                RunOutcome(
                    command = command,
                    exitCode = exit,
                    output = bounded.ifBlank { "(no output)" },
                    refused = false,
                    timedOut = timedOut,
                    durationMillis = System.currentTimeMillis() - startedAt,
                )
            } catch (throwable: Throwable) {
                RunOutcome(
                    command = command,
                    exitCode = null,
                    output = "refused: the command could not be executed (${throwable.message ?: "runtime error"}). " +
                        "Adapt or produce DONE: with what you have.",
                    refused = true,
                    timedOut = false,
                    durationMillis = System.currentTimeMillis() - startedAt,
                )
            } finally {
                runCatching { outputFile.delete() }
            }
        }
    }

    companion object {
        /**
         * Injects the credential helper into git network commands. The helper
         * text is single-quoted so the OUTER shell never expands the token
         * variable — git's helper reads the env var at ask time. Non-git
         * commands pass through unchanged. Pure — JVM-tested directly.
         */
        fun assembleCommand(command: String, tokenEnvVars: Set<String>): String {
            if (!ZaiRunCredentials.isGitNetworkCommand(command)) return command
            val args = ZaiRunCredentials.gitNetworkArgs(tokenEnvVars)
            if (args.isEmpty()) return command
            val helperFlags = args.joinToString(" ") { arg -> "'$arg'" }
            // "git push origin main" → "git <helper flags> push origin main".
            // Plain string surgery: Regex-replacement forms would try to
            // interpret "$GITHUB_TOKEN" inside the helper as a group
            // reference (Illegal group reference) — there is no regex here.
            val rest = if (command == "git") "" else command.removePrefix("git")
            return "git $helperFlags$rest"
        }

        /** Keeps the TAIL of the output (most recent lines matter most). Pure. */
        fun boundTail(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val tail = text.takeLast(maxChars)
            val firstLineBreak = tail.indexOf('\n')
            val clean = if (firstLineBreak >= 0) tail.substring(firstLineBreak + 1) else tail
            return "[… earlier output trimmed …]\n$clean"
        }
    }
}
