package dev.heisluka.mobakula

/** Do not let callbacks from a dismissed editor replace another note's draft. */
internal fun editorInputAccepted(
    ownerNoteId: String,
    activeNoteId: String?,
    busy: Boolean,
): Boolean = !busy && ownerNoteId == activeNoteId
