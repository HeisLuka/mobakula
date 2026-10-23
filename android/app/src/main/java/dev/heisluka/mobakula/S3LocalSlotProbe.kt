package dev.heisluka.mobakula

import java.io.IOException
import org.json.JSONObject

/** Read-only app-private slot comparison. Never binds a remote UUID to a note. */
internal object S3LocalSlotProbe {
    internal data class Scan(
        val checked: Boolean = false,
        val absent: Int = 0,
        val identicalButUnbound: Int = 0,
        val conflicting: Int = 0,
        val unsafe: Int = 0,
    )

    internal fun inspect(session: String, pinnedHead: ByteArray, candidates: Int): Scan {
        val reply = JSONObject(NativeBridge.inspectS3LocalSlots(session, pinnedHead))
        if (!reply.optBoolean("ok", false)) {
            throw IOException("Локальная проверка S3 не пройдена")
        }
        val data = reply.getJSONObject("data")
        val checked = data.getBoolean("localSlotsChecked")
        val inspected = data.getInt("slotsExamined")
        val scan = Scan(
            checked = checked,
            absent = data.getInt("absent"),
            identicalButUnbound = data.getInt("identicalButUnbound"),
            conflicting = data.getInt("conflicting"),
            unsafe = data.getInt("unsafe"),
        )
        return validate(
            scan, inspected, candidates,
            localIdentityBound = data.optBoolean("localIdentityBound", true),
            importAuthorized = data.optBoolean("importAuthorized", true),
            remoteOwnerVerified = data.optBoolean("remoteOwnerVerified", true),
            synced = data.optBoolean("synced", true),
        )
    }

    /** Pure, offline-testable fail-closed accounting contract. */
    internal fun validate(
        scan: Scan, inspected: Int, candidates: Int,
        localIdentityBound: Boolean, importAuthorized: Boolean,
        remoteOwnerVerified: Boolean, synced: Boolean,
    ): Scan {
        val counts = listOf(scan.absent, scan.identicalButUnbound, scan.conflicting, scan.unsafe)
        if (localIdentityBound || importAuthorized || remoteOwnerVerified || synced ||
            candidates < 0 || inspected < 0 || counts.any { it < 0 } ||
            (scan.checked && (inspected != candidates || counts.sum() != inspected)) ||
            (!scan.checked && (inspected != 0 || counts.any { it != 0 }))
        ) {
            throw IOException("Недостоверный локальный статус S3")
        }
        return scan
    }
}
