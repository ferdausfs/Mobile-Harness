package com.jarves.mh.zweb

/**
 * Maps engine run phases onto the project-chat UI: the live process card
 * ("sending" / "generating" / "reading answer") and the terminal notice card
 * (clear errors with a sign-in affordance). Pure logic so it is JVM-testable;
 * the ViewModel only applies the results.
 */
object ZaiChatTurnPresenter {

    /** Progress line for the chat's live process card; null = keep the current line. */
    fun progressTitle(phase: ZaiRunPhase): String? = when (phase) {
        ZaiRunPhase.OPENING_PAGE -> "Connecting to Z.ai"
        ZaiRunPhase.SENDING -> "Sending to Z.ai"
        ZaiRunPhase.GENERATING -> "Z.ai is generating"
        ZaiRunPhase.EXTRACTING -> "Reading answer"
        else -> null
    }

    fun progressDetail(phase: ZaiRunPhase): String? = when (phase) {
        ZaiRunPhase.OPENING_PAGE -> "Opening chat.z.ai…"
        ZaiRunPhase.SENDING -> "Sending your message and project context…"
        ZaiRunPhase.GENERATING -> "Z.ai is writing the answer…"
        ZaiRunPhase.EXTRACTING -> "Reading Z.ai's reply…"
        else -> null
    }

    enum class NoticeKind { NEEDS_LOGIN, CAPTCHA, TIMEOUT, ERROR, CANCELED }

    data class TurnNotice(
        val kind: NoticeKind,
        val message: String,
        /** True when the notice card should offer the Z.ai sign-in sheet button. */
        val showSignIn: Boolean,
    )

    /**
     * Terminal notice for a finished (non-DONE) turn. DONE returns null —
     * that path belongs to the apply pipeline, not to the error card.
     */
    fun terminalNotice(phase: ZaiRunPhase, runMessage: String?): TurnNotice? = when (phase) {
        ZaiRunPhase.NEEDS_LOGIN -> TurnNotice(
            kind = NoticeKind.NEEDS_LOGIN,
            message = "Z.ai needs you to sign in before it can answer. Sign in once from the sheet, then send your message again.",
            showSignIn = true,
        )
        ZaiRunPhase.CAPTCHA -> TurnNotice(
            kind = NoticeKind.CAPTCHA,
            message = "chat.z.ai is showing a verification puzzle. Open the sign-in sheet, solve it once, then send your message again.",
            showSignIn = true,
        )
        ZaiRunPhase.TIMEOUT -> TurnNotice(
            kind = NoticeKind.TIMEOUT,
            message = runMessage ?: "Z.ai took too long to answer. Try a smaller task or send the message again.",
            showSignIn = false,
        )
        ZaiRunPhase.ERROR -> TurnNotice(
            kind = NoticeKind.ERROR,
            message = runMessage ?: "Z.ai Web error. Please try again.",
            showSignIn = false,
        )
        ZaiRunPhase.CANCELED -> TurnNotice(
            kind = NoticeKind.CANCELED,
            message = "Stopped. Z.ai may still finish this turn in the background; send again to continue.",
            showSignIn = false,
        )
        else -> null
    }
}
