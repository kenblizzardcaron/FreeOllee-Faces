package com.blizzardcaron.freeolleefaces.activity

/** Who owns the current pause. MANUAL takes precedence: auto-resume only lifts an AUTO pause. */
enum class PauseSource { NONE, MANUAL, AUTO }
