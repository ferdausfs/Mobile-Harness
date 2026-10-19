package com.jarves.mh.zweb

/**
 * Git credential handling for the autonomous loop (Phase 4, hard rule): the
 * token must reach git WITHOUT being written into .git/config, remote URLs,
 * shell history or files.
 *
 * Mechanism: a per-invocation inline credential helper — `git -c
 * credential.helper=…` — which reads the token from the ENVIRONMENT at the
 * moment git asks for it. Nothing is persisted anywhere:
 *  - `-c` is process-scoped (never touches .git/config or global config),
 *  - the helper's output goes straight to git's in-memory credential cache,
 *  - the command TEXT the model produced contains no secret at all (the
 *    helper references the variable name; the executor injects the value as
 *    an env var),
 *  - GIT_TERMINAL_PROMPT=0 stops git from hanging on a credential prompt.
 *
 * Pure and JVM-tested.
 */
object ZaiRunCredentials {

    /** Env var names consulted for git hosts, in priority order. */
    private val GIT_TOKEN_VARS = listOf("GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN")

    /**
     * Extra argv entries for a git network command when a token env var is
     * available. Empty list = no credential setup (git runs as-is).
     *
     * The helper script is inline (`!f(){ … }; f`), reads ONLY the variable
     * NAMES, and prints the value to git's stdin — never to the terminal.
     */
    fun gitNetworkArgs(availableEnvVars: Set<String>): List<String> {
        val tokenVar = GIT_TOKEN_VARS.firstOrNull { it in availableEnvVars } ?: return emptyList()
        // ORDER MATTERS: the empty helper value RESETS any persisted helper
        // list first; the inline helper then becomes the only one for this
        // invocation. Nothing reaches .git/config.
        return listOf(
            "-c",
            "credential.helper=",
            "-c",
            // username is a constant placeholder; the token IS the password
            // (GitHub's documented app-installation token flow). The helper
            // reads the env var at ask time — the value is never in argv.
            "credential.helper=!f(){ echo username=x-access-token; echo password=\$$tokenVar; }; f",
        )
    }

    /**
     * Env entries every git network command gets: no terminal prompts, and
     * askpass disabled so the only credential path is the inline helper.
     */
    fun gitNetworkEnv(): Map<String, String> = mapOf(
        "GIT_TERMINAL_PROMPT" to "0",
        "GIT_ASKPASS" to "echo",
        "GIT_CONFIG_NOSYSTEM" to "1",
    )

    /** True when the command is a git network operation needing credentials. */
    fun isGitNetworkCommand(command: String): Boolean =
        Regex("(^|\\s)git\\s+(push|clone|fetch|pull|ls-remote|remote\\s+add)\\b").containsMatchIn(command.trim())

    /** True when the command text attempts to write the token into git config. */
    fun writesTokenToConfig(command: String, tokenEnvVars: Set<String>): Boolean {
        val lower = command.lowercase()
        if (!lower.contains("git config") && !lower.contains("git remote set-url")) return false
        return tokenEnvVars.any { varName ->
            lower.contains(varName.lowercase()) || command.contains("$" + varName) || command.contains("\${$varName}")
        }
    }
}
