package dev.heisluka.mobakula

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit opt-in S3 LIVE acceptance on a disposable Android emulator only.
 * Credentials are NEVER test arguments, assets, or logged. Host stages an
 * encrypted Core invitation and its password in app-private no_backup storage,
 * then removes both immediately after first import. This is NOT sync approval.
 *
 * Requires the script scripts/android/yandex_live_receive_probe.py; normal CI
 * compiles this class but never invokes these tests without a real test bucket.
 */
@RunWith(AndroidJUnit4::class)
class S3LiveReceiveDeviceTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val staging get() = File(app.noBackupFilesDir, "s3-live-fixture")

    private fun ensureDisposableEmulator() {
        val hardware = Build.HARDWARE.lowercase()
        check(hardware.contains("ranchu") || hardware.contains("goldfish")) {
            "Live test is forbidden outside a disposable emulator"
        }
    }

    private fun metadata(): JSONObject {
        val index = File(staging, "index.json")
        check(index.isFile && !java.nio.file.Files.isSymbolicLink(index.toPath()))
        val bytes = index.readBytes()
        check(bytes.size in 100..8192)
        val meta = JSONObject(String(bytes, Charsets.UTF_8))
        val bucket = meta.getString("bucket")
        val prefix = meta.getString("prefix")
        check(meta.getString("provider") == "yandex" &&
              bucket.matches(Regex("akula-e2e-[a-z0-9-]{1,50}")) &&
              prefix.startsWith("akula-e2e/") &&
              !meta.getBoolean("remoteOwnerVerified") &&
              !meta.getBoolean("syncAuthorized"))
        return meta
    }

    private fun scope(meta: JSONObject): String {
        val id = meta.getString("vaultId")
        check(id.matches(Regex("[0-9a-f-]{36}")))
        return "e2e-" + id.replace("-", "").take(25)
    }

    private fun state(meta: JSONObject) =
        File(app.noBackupFilesDir, "s3-live-state/" + scope(meta) + ".json")

    private fun vaultRoot(meta: JSONObject) =
        File(app.noBackupFilesDir, "s3-live-vault-" + scope(meta))

    private fun call(command: JSONObject): JSONObject =
        JSONObject(NativeBridge.request(command.toString()))

    private fun requireOk(command: JSONObject): JSONObject {
        val response = call(command)
        assertTrue(response.toString().take(400), response.optBoolean("ok", false))
        return response.getJSONObject("data")
    }

    private fun initVault(meta: JSONObject) {
        requireOk(JSONObject().put("action", "init")
            .put("root", vaultRoot(meta).absolutePath))
    }

    private fun receipt(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, Bundle().apply { putString("akula_s3_live", name) },
        )
    }

    private fun installTestInvitation(store: S3ProtectedInviteStore) {
        check(!store.exists()) { "Test profile already exists; never overwrite" }
        val envelopeFile = File(staging, "invite.akula-sync")
        val passFile = File(staging, "password.bin")
        check(envelopeFile.isFile && passFile.isFile)
        val envelope = envelopeFile.readBytes()
        val passBytes = passFile.readBytes()
        check(envelope.size in 32..16384 && passBytes.size in 20..1024)
        val password = String(passBytes, Charsets.UTF_8).toCharArray()
        try {
            store.saveNew(envelope, password)
        } finally {
            passBytes.fill(0)
            password.fill('\u0000')
            envelope.fill(0)
            // No credential-bearing staging file may survive this step.
            check(envelopeFile.delete())
            check(passFile.delete())
        }
    }

    @Test
    fun importExactlyOneRemoteMarkdownAfterPinnedGenesis() {
        ensureDisposableEmulator()
        val meta = metadata()
        val journal = state(meta)
        check(!journal.exists()) { "This test vault has already run; no repeat" }
        initVault(meta)
        val store = S3ProtectedInviteStore(app, scope(meta))
        val checkpoint = S3CheckpointStore(app, scope(meta))
        check(checkpoint.read() == null)
        installTestInvitation(store)

        val first = S3ReadOnlyProbe.run(store, checkpoint)
        assertEquals(1L, first.latestGeneration)
        assertEquals(1, first.distinctDocumentIds)
        assertEquals(1, first.verifiedBlobCount)
        assertTrue(first.contentPreviewSupported)
        assertEquals("first", first.checkpointRelation)
        assertTrue(first.bindingScan.available)
        assertEquals(1, first.bindingScan.unmapped)
        val selected = first.manualImportCandidates.single()
        assertEquals(meta.getString("documentId"), selected.documentId)
        assertEquals(meta.getString("revisionId"), selected.revisionId)
        val blobKey = meta.getJSONArray("objects")
            .getJSONObject(0).getString("key")
        assertEquals(blobKey.substringAfterLast("/"), selected.blobHash)

        val proposal = first.checkpointProposal
            ?: throw IOException("Missing complete S3 checkpoint proof")
        checkpoint.pinVerified(proposal.candidate, proposal.expectedPrior)
        val stable = S3ReadOnlyProbe.run(store, checkpoint)
        assertEquals("unchanged", stable.checkpointRelation)
        assertEquals(selected, stable.manualImportCandidates.single())

        // This is the ONE manual import action expressly authorized by the
        // opt-in host script. S3 cannot provide local paths or mutate notes.
        val imported = S3ReadOnlyProbe.run(store, checkpoint, selected)
        assertEquals("unchanged", imported.checkpointRelation)
        val local = imported.importedLocalNoteId
            ?: throw IOException("No File Gate receipt for test Markdown")
        assertNotEquals(selected.documentId, local)
        val note = requireOk(JSONObject().put("action", "read").put("id", local))
        assertEquals(selected.blobHash, note.getString("hash"))
        assertTrue(note.getString("content").startsWith("# Akula S3 acceptance fixture"))
        val listing = call(JSONObject().put("action", "list"))
            .getJSONArray("data")
        assertEquals(1, listing.length())
        assertEquals(local, listing.getJSONObject(0).getString("id"))

        val record = JSONObject().put("schema", 1)
            .put("documentId", selected.documentId)
            .put("revisionId", selected.revisionId)
            .put("blobHash", selected.blobHash)
            .put("localNoteId", local)
            .put("scope", scope(meta))
            .put("remoteOwnerVerified", false)
            .put("synced", false).toString().toByteArray(Charsets.UTF_8)
        val parent = journal.parentFile ?: throw IOException("Missing test state parent")
        check(parent.isDirectory || parent.mkdirs())
        check(journal.createNewFile()) { "Test receipt already exists" }
        FileOutputStream(journal).use {
            it.write(record)
            it.fd.sync()
        }
        receipt("imported_once")
    }

    @Test
    fun coldProcessReopenAndDuplicateReceiveAreFailClosed() {
        ensureDisposableEmulator()
        val meta = metadata()
        val recordFile = state(meta)
        check(recordFile.isFile) { "Prepare stage did not complete; refuse" }
        val record = JSONObject(recordFile.readText(Charsets.UTF_8))
        assertEquals(1, record.getInt("schema"))
        assertEquals(scope(meta), record.getString("scope"))
        assertFalse(record.getBoolean("remoteOwnerVerified"))
        assertFalse(record.getBoolean("synced"))
        initVault(meta)
        val local = record.getString("localNoteId")
        val note = requireOk(JSONObject().put("action", "read").put("id", local))
        assertEquals(record.getString("blobHash"), note.getString("hash"))
        val store = S3ProtectedInviteStore(app, scope(meta))
        val checkpoint = S3CheckpointStore(app, scope(meta))
        val read = S3ReadOnlyProbe.run(store, checkpoint)
        assertEquals("unchanged", read.checkpointRelation)
        assertEquals(1, read.bindingScan.exact)
        val selected = read.manualImportCandidates.single()
        assertEquals(record.getString("documentId"), selected.documentId)
        assertEquals(record.getString("revisionId"), selected.revisionId)

        var rejectedAsExistingBinding = false
        try {
            S3ReadOnlyProbe.run(store, checkpoint, selected)
        } catch (_: S3ManualImport.ExistingSourceBinding) {
            rejectedAsExistingBinding = true
        }
        assertTrue("Require exact existing-binding rejection, not a network error",
            rejectedAsExistingBinding)
        val after = call(JSONObject().put("action", "list"))
            .getJSONArray("data")
        assertEquals(1, after.length())
        assertEquals(local, after.getJSONObject(0).getString("id"))
        assertEquals(record.getString("blobHash"), requireOk(
            JSONObject().put("action", "read").put("id", local)
        ).getString("hash"))
        // Retain the test note and durable journal for audit; remove the
        // protected S3 invitation and its non-exportable Keystore key.
        store.delete()
        assertFalse("Test S3 credentials still persisted", store.exists())
        receipt("reopened_no_duplicate")
    }
}
