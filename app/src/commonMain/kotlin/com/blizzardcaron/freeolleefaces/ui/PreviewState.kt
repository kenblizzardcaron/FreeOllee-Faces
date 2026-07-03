package com.blizzardcaron.freeolleefaces.ui

sealed class PreviewState {
    /** Initial state before any coords are known — typically while the launch-time location fix is in flight. */
    object WaitingForCoords : PreviewState()

    /**
     * A fetch is running. When a value was already on screen it rides along as [previous] so the
     * card keeps displaying it (with an "Updating…" hint) instead of wiping to a bare "Loading…".
     */
    data class Loading(val previous: Ready? = null) : PreviewState()

    /** The fetch resolved cleanly. [payload] is the literal 6-char string that would go to the watch. */
    data class Ready(val payload: String, val human: String) : PreviewState()

    /** The fetch failed. [message] is shown in the card. */
    data class Error(val message: String) : PreviewState()
}

/**
 * The Loading state for a refresh of this preview: keeps the currently displayed [PreviewState.Ready]
 * value (or the one already riding a chained refresh) visible while the new fetch runs.
 */
fun PreviewState.refreshing(): PreviewState.Loading = when (this) {
    is PreviewState.Ready -> PreviewState.Loading(previous = this)
    is PreviewState.Loading -> this
    else -> PreviewState.Loading()
}
