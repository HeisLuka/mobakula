package dev.heisluka.mobakula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Schedules a single save after quiet typing, replacing old deadlines. */
internal class DebouncedAutoSave(
    private val scope: CoroutineScope,
    private val saveWhenDue: () -> Unit,
    private val idleMillis: Long = 1_200L,
) {
    private var pending: Job? = null

    fun schedule() {
        cancel()
        pending = scope.launch {
            delay(idleMillis)
            saveWhenDue()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}

/** An uncertain history write must never cause an automatic retry. */
internal fun canScheduleAutoSave(
    dirty: Boolean,
    busy: Boolean,
    evidence: NoteSaveEvidence,
    blockedAfterError: Boolean,
): Boolean = dirty && !busy && !blockedAfterError &&
    evidence != NoteSaveEvidence.NEEDS_REVIEW
