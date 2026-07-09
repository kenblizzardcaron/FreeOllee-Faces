package com.blizzardcaron.freeolleefaces.notify

/**
 * A background failure worth notifying about. Null elsewhere means "healthy".
 * [retryable] gates the notification's "Retry" action — only failures an immediate re-run
 * can plausibly fix (the watch back in range, the network recovered), not ones needing the
 * app (set up watch/location, grant Health access).
 */
enum class FailureKind(val retryable: Boolean) {
    WATCH_UNREACHABLE(retryable = true),
    WEATHER_FETCH_FAILED(retryable = true),
    SETUP_INCOMPLETE(retryable = false),
    HEALTH_UNAVAILABLE(retryable = false),
}

/** What to do with the single error notification after one worker outcome. */
sealed interface NotifyAction {
    data class Notify(val kind: FailureKind) : NotifyAction
    data object Clear : NotifyAction
    data object Nothing : NotifyAction
}

object NotifyDecision {
    /**
     * Transition-only firing with auto-clear:
     *  - success clears any showing notification, else nothing;
     *  - a failure during the sleep window is suppressed (state unchanged);
     *  - a new or changed failure notifies once; the same failure persisting does nothing.
     */
    fun decide(current: FailureKind?, lastNotified: FailureKind?, inSleep: Boolean): NotifyAction = when {
        current == null -> if (lastNotified != null) NotifyAction.Clear else NotifyAction.Nothing
        inSleep -> NotifyAction.Nothing
        lastNotified != current -> NotifyAction.Notify(current)
        else -> NotifyAction.Nothing
    }
}
