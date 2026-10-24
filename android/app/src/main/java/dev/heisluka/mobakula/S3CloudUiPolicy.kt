package dev.heisluka.mobakula

/** Presentation policy only. Native Core and File Gate retain final authority. */
internal fun S3ReadOnlyProbe.Report.canOfferManualImport(): Boolean =
    checkpointRelation == "unchanged" &&
        contentPreviewSupported &&
        localSlotsChecked &&
        bindingScan.available &&
        bindingScan.unmapped > 0 &&
        importedLocalNoteId == null &&
        manualImportCandidates.isNotEmpty()

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
