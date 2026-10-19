package com.jarves.mh.zweb

import com.jarves.mh.zweb.ZaiRunParser.ParsedReply

/**
 * The autonomous loop's budgets and termination policy (Phase 4). Pure so it
 * is JVM-testable: the ViewModel only executes what this policy decides.
 *
 * Termination: DONE in the reply, NEED_FILES (pause + fulfil), iteration cap,
 * total-output cap, or repetition/no-progress detection (the same command
 * producing the same output 3 rounds in a row is a loop, not work).
 */
object ZaiRunLoopPolicy {

    /** Run autonomy modes. FULL_AUTO is the DEFAULT (user's explicit order). */
    enum class AutonomyMode {
        ASK_EACH_COMMAND,
        SAFE_AUTO,
        FULL_AUTO,
    }

    const val DEFAULT_MAX_ITERATIONS = 40
    const val DEFAULT_MAX_TOTAL_OUTPUT_CHARS = 200_000
    const val DEFAULT_PER_COMMAND_TIMEOUT_MILLIS = 120_000L
    const val MAX_OUTPUT_TO_MODEL_CHARS = 8_000
    /** Identical command+output rounds before the loop declares no progress. */
    const val NO_PROGRESS_ROUNDS = 3

    data class Budget(
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        val maxTotalOutputChars: Int = DEFAULT_MAX_TOTAL_OUTPUT_CHARS,
        val iterationsUsed: Int = 0,
        val totalOutputChars: Int = 0,
        /** Fingerprints of previous rounds (command + output hash) -> count. */
        val roundFingerprints: Map<String, Int> = emptyMap(),
    ) {
        fun withIteration(outputChars: Int, fingerprint: String): Budget = copy(
            iterationsUsed = iterationsUsed + 1,
            totalOutputChars = totalOutputChars + outputChars,
            roundFingerprints = roundFingerprints + (fingerprint to (roundFingerprints[fingerprint] ?: 0) + 1),
        )
    }

    sealed interface Decision {
        /** Execute these commands, then send the results back. */
        data class RunCommands(val commands: List<ZaiRunParser.RunCommand>) : Decision

        /** The task is complete — [summary] is the model's DONE text. */
        data class Finish(val summary: String) : Decision

        /** Pause: the model asked for files; the fulfilment path takes over. */
        data object NeedFiles : Decision

        /** A hard limit was hit — stop with a precise report. */
        data class Exhausted(val reason: String) : Decision

        /** No RUN/DONE — a plain answer (no loop entry needed). */
        data object PlainAnswer : Decision
    }

    fun fingerprint(command: String, output: String): String =
        Integer.toHexString((command.trim() + "\u0000" + output.trim()).hashCode())

    /**
     * Decides what to do with a reply inside the loop. Called after every
     * model turn while a RUN task is active.
     */
    fun nextAction(reply: String, budget: Budget): Decision {
        if (budget.iterationsUsed >= budget.maxIterations) {
            return Decision.Exhausted(
                "Reached the iteration limit ($budget.maxIterations commands). " +
                    "Send the task again to continue from where it stopped.",
            )
        }
        if (budget.totalOutputChars >= budget.maxTotalOutputChars) {
            return Decision.Exhausted(
                "Reached the total output limit (${budget.maxTotalOutputChars} characters of command output). " +
                    "Try a narrower task or run the remaining steps yourself.",
            )
        }
        val noProgress = budget.roundFingerprints.any { it.value >= NO_PROGRESS_ROUNDS }
        if (noProgress) {
            return Decision.Exhausted(
                "Stopped: the last $NO_PROGRESS_ROUNDS rounds produced identical results (no progress). " +
                    "Change the approach or send the task again with more details.",
            )
        }
        val parsed: ParsedReply = ZaiRunParser.parse(reply)
        return when {
            parsed.doneSummary != null -> Decision.Finish(parsed.doneSummary)
            parsed.needsFiles -> Decision.NeedFiles
            parsed.runs.isNotEmpty() -> Decision.RunCommands(parsed.runs)
            else -> Decision.PlainAnswer
        }
    }

    /** Trims one command's output for the trip back to the model (tail-kept). */
    fun boundOutput(output: String, maxChars: Int = MAX_OUTPUT_TO_MODEL_CHARS): String {
        val cleaned = output.trim()
        if (cleaned.length <= maxChars) return cleaned
        return "… (output truncated, showing the last $maxChars characters)\n" + cleaned.takeLast(maxChars)
    }
}
