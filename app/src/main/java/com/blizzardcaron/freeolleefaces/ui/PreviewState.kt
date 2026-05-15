package com.blizzardcaron.freeolleefaces.ui

sealed class PreviewState {
    /** Initial state before any coords are known — typically while the launch-time location fix is in flight. */
    object WaitingForCoords : PreviewState()

    /** Coords are known and a temperature / sun-event fetch is running. */
    object Loading : PreviewState()

    /** The fetch resolved cleanly. [payload] is the literal 6-char string that would go to the watch. */
    data class Ready(val payload: String, val human: String) : PreviewState()

    /** The fetch failed. [message] is shown in the card. */
    data class Error(val message: String) : PreviewState()

    /** Sun-time only: no rise/set event in the next 24h (polar). */
    object NoEvent : PreviewState()
}
