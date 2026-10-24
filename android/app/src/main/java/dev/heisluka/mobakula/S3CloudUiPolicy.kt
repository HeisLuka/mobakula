package dev.heisluka.mobakula

/** Presentation policy only. Native Core and File Gate retain final authority. */
internal fun S3ReadOnlyProbe.Report.canOfferManualImport(): Boolean =
    checkpointRelation == "unchanged" &&
        contentPreviewSupported &&
        localSlotsChecked &&
        bindingScan.available &&
        bindingScan.unmapped > 0 &&
        importedLocalNoteId == null &&
        manualImportCandidates.any {
            bindingScan.states[it.documentId] == S3BindingProbe.State.UNMAPPED
        }

internal fun S3ReadOnlyProbe.Report.canOfferCandidate(
    candidate: S3ManualImport.Candidate,
): Boolean = canOfferManualImport() &&
    candidate in manualImportCandidates &&
    bindingScan.states[candidate.documentId] == S3BindingProbe.State.UNMAPPED

internal fun S3BindingProbe.State.displayStatus(): String = when (this) {
    S3BindingProbe.State.UNMAPPED -> "Новая"
    S3BindingProbe.State.EXACT -> "Уже получена"
    S3BindingProbe.State.OUTDATED -> "Новая версия — требуется разбор"
    S3BindingProbe.State.PREPARED -> "Незавершённый импорт"
    S3BindingProbe.State.HELD -> "На проверке / HOLD"
}

internal fun S3ReadOnlyProbe.Report.canOfferCheckpointPin(): Boolean =
    checkpointRelation in setOf("first", "advanced") &&
        contentPreviewSupported &&
        checkpointProposal != null

internal fun readableS3Bytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> bytes.toString() + " Б"
    bytes < 1024 * 1024 -> ((bytes + 1023) / 1024).toString() + " КиБ"
    else -> ((bytes + 1024 * 1024 - 1) / (1024 * 1024)).toString() + " МиБ"
}
