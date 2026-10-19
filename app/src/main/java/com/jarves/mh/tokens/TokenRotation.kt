package com.jarves.mh.tokens

/**
 * Rotation state machine (Phase 2, hard requirement): the app tracks which
 * tokens were made available to a task; when the task finishes — done,
 * stopped or failed — those tokens are flagged "needs rotation" until the
 * user taps Rotated (or deletes/replaces the token). Optional per-token
 * auto-disable after the task. NEVER auto-deletes anything.
 */
object TokenRotation {

    enum class TaskEndReason { DONE, STOPPED, FAILED }

    const val MAX_REASON_LENGTH = 120

    /**
     * What a task end does to the used tokens: all of them get the rotation
     * badge; the ones with auto-disable are additionally disabled (never
     * deleted).
     */
    data class TaskEndDecision(
        val flagIds: Set<String>,
        val disableIds: Set<String>,
        /** Human reason carried onto each flagged token, e.g. "Task \"Fix bug\" finished". */
        val rotationReason: String,
    )

    fun onTaskEnded(
        usedTokens: List<StoredToken>,
        taskTitle: String,
    ): TaskEndDecision {
        val reason = "Task \"${taskTitle.take(MAX_REASON_LENGTH)}\" finished"
        return TaskEndDecision(
            flagIds = usedTokens.map { it.id }.toSet(),
            disableIds = usedTokens.filter { it.autoDisableAfterTask }.map { it.id }.toSet(),
            rotationReason = reason,
        )
    }

    /** Applies [decision] to a record list, returning the updated records. */
    fun apply(records: List<StoredToken>, decision: TaskEndDecision, nowMillis: Long): List<StoredToken> =
        records.map { record ->
            var next = record
            if (record.id in decision.flagIds) {
                next = next.copy(
                    needsRotation = true,
                    rotationReason = decision.rotationReason,
                    rotationAtMillis = nowMillis,
                )
            }
            if (record.id in decision.disableIds) {
                next = next.copy(enabled = false)
            }
            next
        }

    /** User tapped "Rotated": clears the badge. */
    fun markRotated(record: StoredToken): StoredToken = record.copy(
        needsRotation = false,
        rotationReason = null,
        rotationAtMillis = null,
    )

    /** Token value replaced: the new secret is fresh, badge clears. */
    fun markReplaced(record: StoredToken): StoredToken = markRotated(record)

    /** Which of [records] would be made available to [projectId] (empty set = all projects). */
    fun availableForProject(records: List<StoredToken>, projectId: String?): List<StoredToken> {
        if (projectId.isNullOrBlank()) return records.filter { it.enabled }
        return records.filter { it.enabled && (it.allowedProjects.isEmpty() || projectId in it.allowedProjects) }
    }

    /** "Task finished. Rotate these tokens now: GitHub, Cloudflare" */
    fun reminderMessage(flagged: List<StoredToken>): String {
        if (flagged.isEmpty()) return ""
        val names = flagged.joinToString(", ") { TokenPresets.byId(it.presetId).title }
        return "Task finished. Rotate these tokens now: $names"
    }
}

/**
 * Data behind the post-task rotation reminder card (and notification):
 * the human message plus one tappable row per flagged provider.
 */
data class TokenRotationNotice(
    val message: String,
    val items: List<Item>,
) {
    data class Item(
        val tokenId: String,
        val title: String,
        /** Provider page where the user rotates the token; null = no page. */
        val managementUrl: String?,
    )
}
