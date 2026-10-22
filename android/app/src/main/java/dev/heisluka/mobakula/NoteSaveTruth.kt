package dev.heisluka.mobakula

/** Evidence for this editor session; not a claim of power-loss durability. */
internal enum class NoteSaveEvidence {
    VERIFIED,     // Core returned a verified primary file + history result.
    FILE_READ,    // Disk bytes were read; their history has not been verified.
    NEEDS_REVIEW, // Primary file may have committed, history is uncertain.
}

internal fun noteSaveStatus(dirty: Boolean, evidence: NoteSaveEvidence): String = when {
    evidence == NoteSaveEvidence.NEEDS_REVIEW && dirty ->
        "Есть изменения; предыдущее сохранение требует проверки"
    evidence == NoteSaveEvidence.NEEDS_REVIEW ->
        "Файл существует, но история не подтверждена"
    dirty -> "Есть несохранённые изменения"
    evidence == NoteSaveEvidence.VERIFIED ->
        "Файл и история проверены"
    else -> "Файл прочитан; история не проверена"
}

internal fun canSaveNote(dirty: Boolean, evidence: NoteSaveEvidence): Boolean =
    dirty && evidence != NoteSaveEvidence.NEEDS_REVIEW
