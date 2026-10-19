package com.jarves.mh.zweb

/**
 * The hard rails of the autonomous RUN loop (Phase 4). These do NOT ask the
 * user — they just refuse, and the refusal ("refused: <reason>") goes back to
 * the model so it can adapt.
 *
 * Threat model (per the user's spec): even in full auto the loop must refuse
 * rm -rf outside the workspace or on / and ~, force pushes, history rewrite
 * on protected branches, deleting remote repos/projects/databases, disk and
 * system-level commands, fork bombs, curl|sh from unknown hosts, reading
 * other apps' data, and printing env/token values.
 *
 * Bypass hardening: the command is SEGMENTED on shell operators (&&, ||, ;, |,
 * $( ), backticks) and every segment is evaluated; quoting is normalized;
 * variable/subshell expansions in destructive argument positions refuse (the
 * target of a recursive delete must be a literal path); base64-pipe-into-
 * shell is refused; classic fork-bomb signatures are refused. No denylist can
 * be perfect — the honest residual risks are documented in AGENT_LOG.
 */
object ZaiRunDenylist {

    data class Decision(
        val refused: Boolean,
        val reason: String?,
    ) {
        companion object {
            val ALLOW = Decision(false, null)
            fun refuse(reason: String) = Decision(true, reason)
        }
    }

    const val MAX_COMMAND_CHARS = 2_000

    private val FORCE_PUSH = Regex("\\bgit\\s+push\\b[^;|&]*(?:\\s-f\\b|\\s--force\\b|\\s--force-with-lease\\b|\\s--delete\\b)")
    private val HISTORY_REWRITE = Regex("\\bgit\\s+(?:rebase|filter-branch|replace|filter-repo)\\b|\\bbfg\\b")
    private val REMOTE_DESTRUCTION = Regex(
        "(?i)\\b(?:gh\\s+repo\\s+delete|gcloud\\s+projects?\\s+delete|firebase\\s+projects?:delete|" +
            "vercel\\s+(?:project\\s+)?(?:rm|remove|delete)|supabase\\s+projects?\\s+delete|" +
            "drop\\s+database|drop\\s+table|dropdb|dropuser|wrangler\\s+kv\\s+.*delete|npm\\s+unpublish)\\b",
    )
    private val SYSTEM_COMMANDS = Regex(
        "(?i)(?:^|[;&|]\\s*|\\s)(?:mkfs(\\.\\w+)?|fdisk|parted|dd\\s+if=/dev/|shutdown|reboot|poweroff|halt|" +
            "pm\\s+|am\\s+(?:start|force-stop)|settings\\s+(?:put|delete)|svc\\s+|mount\\s+-o\\s+remount|umount)\\b",
    )
    private val FORK_BOMB = Regex(":\\s*\\(\\s*\\)\\s*\\{.*\\}|(?i)(?:while|until)\\s+[^;]*;?\\s*do\\b.*&.*(?:done|$)")
    private val PIPE_TO_SHELL = Regex(
        "(?i)(?:curl|wget|fetch)\\b[^;|&]*\\|\\s*(?:sudo\\s+)?(?:ba|z|da|k)?sh\\b|" +
            "(?i)(?:curl|wget)\\b[^;|&]*\\|\\s*(?:python|perl|ruby|node)\\b",
    )
    private val BASE64_TO_SHELL = Regex(
        "(?i)base64\\s+(?:-d|-D|--decode)[^;|&]*\\|\\s*(?:sudo\\s+)?(?:ba|z|da|k)?sh\\b|" +
            "(?i)\\|\\s*(?:sudo\\s+)?(?:ba|z|da|k)?sh\\s*(?:-c)?\\s*(?:<|\\Z)",
    )
    private val OTHER_APPS_DATA = Regex(
        "(?i)(?:^|[\\s\"'])/(?:data/data/|data/user/|sdcard/Android/(?:data|obb)/|proc/[0-9]+/environ)",
    )
    private val PRIVILEGE_ESCAPE = Regex("(?i)(?:^|[;&|]\\s*|\\s)(?:su\\b|sudo\\b|nsenter|unshare|chroot\\s+/)")
    private val ENV_DUMP = Regex(
        "(?i)(?:^|[;&|]\\s*|\\s)(?:printenv|env|declare\\s+-x|export\\s+-p|setenv)\\b|" +
            "(?i)\\bcat\\s+/proc/(?:self|\\d+)/environ\\b",
    )
    private val TOKEN_ECHO = Regex("(?i)\\b(?:echo|printf|tee)\\b[^;|&]*\\$\\{?[A-Z][A-Z0-9_]*(?:TOKEN|KEY|SECRET)[A-Z0-9_]*\\}?")

    /** Token env var names the loop knows are injected (for the echo rule). */
    private val KNOWN_TOKEN_VARS = listOf(
        "GITHUB_TOKEN", "CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID",
        "VERCEL_TOKEN", "FIREBASE_TOKEN", "SUPABASE_ACCESS_TOKEN", "NPM_TOKEN",
    )

    /**
     * Evaluates one command. [workspaceGuestPath] is the command's cwd inside
     * the guest (e.g. /workspace); targets under it are the user's own
     * project and allowed even when destructive. [tokenEnvVars] are the names
     * actually injected for this task (extends the echo rule).
     */
    fun evaluate(command: String, workspaceGuestPath: String = "/workspace", tokenEnvVars: Set<String> = KNOWN_TOKEN_VARS.toSet()): Decision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision.refuse("empty command")
        if (trimmed.length > MAX_COMMAND_CHARS) return Decision.refuse("command too long (max $MAX_COMMAND_CHARS characters)")

        // Whole-command patterns first: pipe chains span segments, so
        // `curl ... | sh` and `... | base64 -d | sh` are invisible per-segment.
        PIPE_TO_SHELL.find(trimmed)?.let { return Decision.refuse("piping downloads into a shell refused (curl|sh class)") }
        BASE64_TO_SHELL.find(trimmed)?.let { return Decision.refuse("base64-decoded content piped into a shell refused") }
        FORK_BOMB.find(trimmed)?.let { return Decision.refuse("fork-bomb pattern refused") }

        // Split on operators so "echo safe && rm -rf /" evaluates BOTH parts.
        val segments = segment(trimmed)
        for (segment in segments) {
            evaluateSegment(segment, workspaceGuestPath, tokenEnvVars)?.let { return it }
        }
        // Bypass rail: `cd /somewhere && rm -rf something` — a relative delete
        // target after the cwd left the workspace is NOT workspace-relative
        // anymore. (cd /tmp stays allowed as the standard scratch area.)
        val cdEscapes = segments.dropLast(1).any { seg -> cdEscapesWorkspace(seg, workspaceGuestPath) }
        if (cdEscapes && segments.drop(1).any { DELETE_COMMAND.containsMatchIn(it) }) {
            return Decision.refuse("delete after cd outside the workspace refused — use absolute /workspace paths")
        }
        // Any segment that is a bare interpreter reached via a pipe is the
        // same curl|sh class (the whole-command regexes above catch the
        // downloader forms; this catches hand-assembled chains).
        if (segments.size > 1 && segments.last().matches(Regex("(?i)(?:ba|z|da|k)?sh|python3?|perl|ruby|node"))) {
            return Decision.refuse("piping into a shell interpreter refused")
        }
        return Decision.ALLOW
    }

    private val CD_COMMAND = Regex("(?:^|\\s)cd\\s+(\"[^\"]+\"|\\S+)")

    private fun cdEscapesWorkspace(segment: String, workspaceGuestPath: String): Boolean {
        val match = CD_COMMAND.find(segment) ?: return false
        val target = match.groupValues[1].removeSurrounding("\"")
        if (!target.startsWith("/")) return false
        // ANY absolute cd outside the workspace (including /tmp) makes later
        // relative delete targets unsafe — the combination rule below refuses
        // `cd X && rm …` with relative targets; non-delete work in /tmp stays fine.
        return isUnsafeDeleteTarget(target, workspaceGuestPath)
    }

    private fun evaluateSegment(rawSegment: String, workspaceGuestPath: String, tokenEnvVars: Set<String>): Decision? {
        // Normalize: strip one layer of surrounding quotes, collapse whitespace.
        val segment = rawSegment
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (segment.isEmpty()) return null

        FORCE_PUSH.find(segment)?.let { return Decision.refuse("git force push / branch deletion refused — this is a protected operation") }
        HISTORY_REWRITE.find(segment)?.let { return Decision.refuse("git history rewrite (rebase/filter-branch) refused — protected operation") }
        REMOTE_DESTRUCTION.find(segment)?.let { return Decision.refuse("remote/project/database deletion refused — protected operation") }
        SYSTEM_COMMANDS.find(segment)?.let { return Decision.refuse("disk or system-level command refused") }
        PIPE_TO_SHELL.find(segment)?.let { return Decision.refuse("piping downloads into a shell refused (curl|sh class)") }
        BASE64_TO_SHELL.find(segment)?.let { return Decision.refuse("base64-decoded content piped into a shell refused") }
        PRIVILEGE_ESCAPE.find(segment)?.let { return Decision.refuse("privilege escalation (su/sudo/chroot) refused") }
        ENV_DUMP.find(segment)?.let { return Decision.refuse("dumping the environment refused — tools read the variables themselves") }
        OTHER_APPS_DATA.find(segment)?.let { return Decision.refuse("reading other apps' data refused") }

        TOKEN_ECHO.find(segment)?.let { return Decision.refuse("printing a token value refused — the model only ever sees variable NAMES") }
        tokenEnvVars.forEach { name ->
            if (Regex("(?i)\\b(?:echo|printf|tee)\\b[^;|&]*\\$\\{?$name\\}?").containsMatchIn(segment)) {
                return Decision.refuse("printing $name refused — tools read it from the environment themselves")
            }
        }

        // Deletes of ANY kind: targets must stay inside the workspace. This
        // covers both recursive (`rm -rf …`) and plain (`rm /etc/passwd`).
        DELETE_COMMAND.find(segment)?.let { match ->
            val tail = segment.substring(match.range.last + 1)
            val flagsRemoved = tail.replace(Regex("(?:^|\\s)-{1,2}[A-Za-z-]+"), " ").trim()
            if (flagsRemoved.contains('$') || flagsRemoved.contains('`') || flagsRemoved.contains("$(")) {
                return Decision.refuse("delete with a variable/subshell target refused — name the path literally")
            }
            val bad = flagsRemoved.split(Regex("\\s+")).firstOrNull { target ->
                isUnsafeDeleteTarget(target.trim().removeSurrounding("\"").removeSurrounding("'"), workspaceGuestPath)
            }
            if (bad != null) {
                return Decision.refuse("delete outside the workspace refused (target: $bad)")
            }
        }
        return null
    }

    private val DELETE_COMMAND = Regex("(?:^|\\s)(?:rm|rmdir)\\s")

    /**
     * A recursive-delete target is unsafe when it escapes the workspace:
     * absolute non-workspace paths, `..` escape, guest home, `/`, `~`, or a
     * path that normalizes out of the workspace root.
     */
    internal fun isUnsafeDeleteTarget(target: String, workspaceGuestPath: String): Boolean {
        val t = target.trim().trimEnd('/')
        if (t.isEmpty() || t == "..") return true
        if (t == "/" || t == "~" || t == "~/" || t == "\$HOME" || t == "\${HOME}") return true
        if (t.startsWith("/")) {
            if (t == workspaceGuestPath) return false
            if (t.startsWith("$workspaceGuestPath/")) {
                // Inside the workspace: still refuse `..` escapes.
                return normalizesOutside(t, workspaceGuestPath)
            }
            // Other absolute guest paths (guest home, /etc, /usr, ...) — refuse:
            // the workspace is the blast radius this feature is allowed to have.
            return true
        }
        if (t.startsWith("~")) return true
        // Relative: refused when it climbs out of the workspace.
        return normalizesOutside(t, workspaceGuestPath)
    }

    private fun normalizesOutside(path: String, root: String): Boolean {
        val rootParts = root.trim('/').split('/').filter { it.isNotEmpty() }
        val parts = ArrayList(rootParts)
        for (part in path.trim('/').split('/').filter { it.isNotEmpty() && it != "." }) {
            if (part == "..") {
                if (parts.isEmpty()) return true
                parts.removeAt(parts.size - 1)
            } else {
                parts.add(part)
            }
        }
        val resolved = parts.joinToString("/")
        val rootNorm = rootParts.joinToString("/")
        return resolved != rootNorm && !resolved.startsWith("$rootNorm/")
    }

    /** Splits a command on &&, ||, ;, | and $()/backtick boundaries. */
    internal fun segment(command: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < command.length) {
            val c = command[i]
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }
                c == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                    out.add(current.toString()); current.clear(); i++
                }
                c == '|' && i + 1 < command.length && command[i + 1] == '|' -> {
                    out.add(current.toString()); current.clear(); i++
                }
                c == ';' || c == '|' -> {
                    out.add(current.toString()); current.clear()
                }
                c == '`' -> {
                    out.add(current.toString()); current.clear()
                }
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    out.add(current.toString()); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out.add(current.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ── Safe-auto allowlist ──

    /**
     * SAFE AUTO mode: runs without asking, but only commands whose FIRST word
     * is on this read/build/test allowlist. Anything else is refused with an
     * explanation (the model can ask the user to switch modes).
     */
    private val SAFE_AUTO_FIRST_WORDS = setOf(
        "ls", "cat", "head", "tail", "grep", "rg", "find", "wc", "file", "stat", "du", "df",
        "git", "node", "npm", "npx", "python", "python3", "pip", "pytest", "java", "javac",
        "gradle", "./gradlew", "make", "cargo", "go", "tsc", "eslint", "echo", "pwd", "which",
        "date", "sleep", "true", "sed", "awk", "cut", "sort", "uniq", "diff", "tree",
    )

    fun safeAutoAllowed(command: String): Boolean {
        if (SAFE_AUTO_DENIED.containsMatchIn(command)) return false
        val firstSegment = segment(command).firstOrNull()?.trim() ?: return false
        val words = firstSegment.split(Regex("\\s+"))
        val first = words.firstOrNull() ?: return false
        val base = first.substringAfterLast('/')
        if (base !in SAFE_AUTO_FIRST_WORDS && first !in SAFE_AUTO_FIRST_WORDS) return false
        // git in safe-auto: read-only subcommands only (push/rebase/reset are
        // write or destructive operations).
        if (base == "git") {
            val sub = words.getOrNull(1)?.lowercase() ?: return false
            return sub in setOf("status", "log", "diff", "show", "branch", "remote", "tag", "ls-files", "rev-parse", "describe", "stash", "list")
        }
        return true
    }

    /** Write-side package/publish operations never pass safe-auto. */
    private val SAFE_AUTO_DENIED = Regex(
        "(?i)\\b(?:npm\\s+(?:publish|uninstall)|pip\\s+uninstall|cargo\\s+publish|gem\\s+push|" +
            "gradlew?\\s+publish|git\\s+(?:push|reset|rebase|clean))\\b",
    )
}
