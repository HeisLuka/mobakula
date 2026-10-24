package dev.heisluka.mobakula

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * Transport-only Android adapter. Rust signs a fixed Yandex host and checks
 * Core manifest ancestry. The result is proof of bounded remote *reads*, not
 * owner authorization, synced notes, or a File Gate write permit.
 *
 * Caller MUST run on Dispatchers.IO. Never print signed Authorization headers.
 */
internal object S3ReadOnlyProbe {
    private const val MAX_ROOT = 4096
    private const val MAX_MANIFEST = 512 * 1024
    private const val MAX_SHARD = 128 * 1024
    private const val MAX_REVISION_JSON = 64 * 1024
    private const val MAX_REVISION_PREVIEW = 64
    private const val MAX_BLOB_COUNT = 16
    private const val MAX_BLOB_BYTES = 1024 * 1024
    private const val MAX_BLOB_TOTAL = 8L * 1024 * 1024
    private const val OBJECT_MANIFEST = 0
    private const val OBJECT_REVISION = 1
    private const val OBJECT_BLOB = 2
    private const val MAX_PREVIEW_SHARDS = 16
    private const val MAX_CHAIN_TOTAL = 8 * 1024 * 1024
    private const val MAX_ANCESTORS = 128
    private val ETA = Regex("\"[A-Za-z0-9_.-]{1,128}\"")
    private val CLOCK = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    internal data class Report(
        val latestGeneration: Long,
        val manifestsChecked: Int,
        val latestRevisions: Int,
        val distinctDocumentIds: Int = 0,
        val validatedShards: Int = 0,
        val verifiedOnlyBytes: Boolean = true,
        val metadataRevisionsChecked: Int = 0,
        val metadataPreviewSupported: Boolean = false,
        val contentPreviewSupported: Boolean = false,
        val verifiedBlobCount: Int = 0,
        val verifiedBlobBytes: Long = 0,
        val receiveCandidatesPreview: Int = 0,
        val receiveNeedsReviewPreview: Int = 0,
        val manualImportCandidates: List<S3ManualImport.Candidate> = emptyList(),
        val importedLocalNoteId: String? = null,
        val localSlotsChecked: Boolean = false,
        val localSlotsAbsent: Int = 0,
        val localSlotsIdenticalUnbound: Int = 0,
        val localSlotsConflicting: Int = 0,
        val localSlotsUnsafe: Int = 0,
        val bindingScan: S3BindingProbe.Scan = S3BindingProbe.Scan(),
        val checkpointRelation: String = "unchecked",
        val checkpointProposal: S3CheckpointProbe.Assessment? = null,
    )
    internal data class Meta(val etag: String, val size: Long)
    internal data class ObjectBytes(val meta: Meta, val bytes: ByteArray)

    private fun json(raw: String): JSONObject {
        val obj = JSONObject(raw)
        if (!obj.optBoolean("ok")) {
            throw IOException("S3 Core: " + obj.optString("code", "rejected"))
        }
        return obj.getJSONObject("data")
    }

    private fun utcStamp(): String = CLOCK.format(Instant.now())

    internal fun isPermittedEndpoint(target: URL): Boolean =
        target.protocol == "https" &&
            target.host == "storage.yandexcloud.net" &&
            target.port == -1 && target.userInfo == null &&
            target.query == null && target.ref == null

    internal fun isPermittedEtag(value: String): Boolean = ETA.matches(value)

    /** Fail closed before reading a body, including on redirects and HTTP errors. */
    internal fun checkedMeta(
        status: Int, etag: String?, contentEncoding: String?,
        length: Long, maxBytes: Int, allowEmpty: Boolean = false,
    ): Meta {
        if (status != HttpURLConnection.HTTP_OK) {
            throw IOException("S3 HTTP " + status + " cannot establish a stable read")
        }
        if (etag == null || !isPermittedEtag(etag)) {
            throw IOException("S3 returned an invalid ETag")
        }
        if (!contentEncoding.isNullOrEmpty() &&
            !contentEncoding.equals("identity", ignoreCase = true)) {
            throw IOException("S3 returned compressed content")
        }
        if (length < (if (allowEmpty) 0L else 1L) ||
            length > maxBytes.toLong()) {
            throw IOException("S3 object outside read bounds")
        }
        return Meta(etag, length)
    }

    private fun execute(
        session: String, method: String, manifestId: String,
        ifMatch: String, maxBytes: Int,
    ): ObjectBytes = executeAuthorized(
        session, method, manifestId, ifMatch, maxBytes, OBJECT_MANIFEST
    )

    private fun executeRevision(
        session: String, method: String, revisionId: String,
        ifMatch: String, maxBytes: Int,
    ): ObjectBytes = executeAuthorized(
        session, method, revisionId, ifMatch, maxBytes, OBJECT_REVISION
    )

    private fun executeBlob(
        session: String, method: String, blobHash: String,
        ifMatch: String, maxBytes: Int,
    ): ObjectBytes = executeAuthorized(
        session, method, blobHash, ifMatch, maxBytes, OBJECT_BLOB
    )

    private fun executeAuthorized(
        session: String, method: String, objectId: String,
        ifMatch: String, maxBytes: Int, kind: Int,
    ): ObjectBytes {
        val stamp = utcStamp()
        val signed = json(when (kind) {
            OBJECT_MANIFEST -> NativeBridge.signS3ReadProbe(
                session, method, objectId, ifMatch, stamp
            )
            OBJECT_REVISION -> NativeBridge.signS3RevisionProbe(
                session, objectId, method, ifMatch, stamp
            )
            OBJECT_BLOB -> NativeBridge.signS3BlobProbe(
                session, objectId, method, ifMatch, stamp
            )
            else -> throw IOException("Недопустимый тип S3-объекта")
        })
        val target = URL(signed.getString("url"))
        // Even a compromised untrusted manifest cannot trigger a redirect or
        // a request outside the fixed Yandex S3 server.
        if (!isPermittedEndpoint(target)) {
            throw IOException("Запрещённый адрес S3")
        }
        val socket = target.openConnection() as? HttpsURLConnection
            ?: throw IOException("TLS-подключение S3 недоступно")
        try {
            socket.instanceFollowRedirects = false
            socket.useCaches = false
            socket.connectTimeout = 5_000
            socket.readTimeout = 10_000
            socket.requestMethod = method
            socket.setRequestProperty("Authorization", signed.getString("authorization"))
            socket.setRequestProperty("x-amz-date", signed.getString("amzDate"))
            socket.setRequestProperty(
                "x-amz-content-sha256", signed.getString("payloadSha256")
            )
            socket.setRequestProperty("Accept-Encoding", "identity")
            if (ifMatch.isNotEmpty()) {
                if (signed.optString("ifMatch") != ifMatch) {
                    throw IOException("Сбой привязки S3 ETag")
                }
                socket.setRequestProperty("If-Match", ifMatch)
            }
            val meta = checkedMeta(
                socket.responseCode, socket.getHeaderField("ETag"),
                socket.getHeaderField("Content-Encoding"),
                socket.contentLengthLong, maxBytes,
                allowEmpty = kind == OBJECT_BLOB,
            )
            if (method == "HEAD") return ObjectBytes(meta, ByteArray(0))
            val buffer = ByteArray(4096)
            val collected = ByteArrayOutputStream()
            socket.inputStream.use { stream ->
                var emptyReads = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n == 0) {
                        if (++emptyReads > 8) throw IOException("S3-поток не отвечает")
                        continue
                    }
                    emptyReads = 0
                    if (collected.size() + n > maxBytes) {
                        throw IOException("S3 превысил лимит объекта")
                    }
                    collected.write(buffer, 0, n)
                }
            }
            val bytes = collected.toByteArray()
            if (bytes.size.toLong() != meta.size) {
                throw IOException("Неполная передача S3-объекта")
            }
            return ObjectBytes(meta, bytes)
        } finally {
            socket.disconnect()
        }
    }

    /** Same production state machine, with an injected transport for offline tests. */
    internal fun readStableFrom(
        session: String, manifestId: String, maxBytes: Int,
        allowEmpty: Boolean = false,
        fetch: (String, String, String, String, Int) -> ObjectBytes,
    ): ObjectBytes {
        val before = fetch(session, "HEAD", manifestId, "", maxBytes).meta
        val body = fetch(session, "GET", manifestId, before.etag, maxBytes)
        val after = fetch(session, "HEAD", manifestId, "", maxBytes).meta
        if (before != after || body.meta != before ||
            body.bytes.size.toLong() != before.size ||
            before.size < (if (allowEmpty) 0L else 1L) ||
            before.size > maxBytes.toLong() || !isPermittedEtag(before.etag)) {
            throw IOException("S3-объект изменился при чтении")
        }
        return body
    }

    private fun readStable(session: String, manifestId: String, maxBytes: Int): ObjectBytes =
        readStableFrom(session, manifestId, maxBytes, fetch = ::execute)

    private fun readStableRevision(session: String, revisionId: String): ObjectBytes =
        readStableFrom(session, revisionId, MAX_REVISION_JSON, fetch = ::executeRevision)

    private fun readStableBlob(session: String, blobHash: String): ObjectBytes =
        readStableFrom(
            session, blobHash, MAX_BLOB_BYTES, allowEmpty = true,
            fetch = ::executeBlob
        )

    private fun parentId(json: JSONObject): String? {
        if (!json.has("previous_manifest_id") || json.isNull("previous_manifest_id")) {
            return null
        }
        val id = json.getString("previous_manifest_id")
        if (id.length != 36) throw IOException("Повреждена цепочка S3")
        return id
    }

    fun run(store: S3ProtectedInviteStore, checkpoints: S3CheckpointStore,
            selectedDocument: String? = null): Report {
        val start = store.open().use { invitation ->
            json(NativeBridge.beginS3ReadProbe(
                invitation.encryptedInvitation, invitation.password
            ))
        }
        val session = start.getString("session")
        val ancestors = ArrayList<ByteArray>()
        val shards = ArrayList<ByteArray>()
        try {
            val first = readStable(session, "", MAX_ROOT)
            val head = JSONObject(String(first.bytes, Charsets.UTF_8))
            var nextId = head.getString("manifest_id")
            val seen = HashSet<String>()
            var total = 0
            while (true) {
                if (!seen.add(nextId) || seen.size > MAX_ANCESTORS) {
                    throw IOException("Цикл или превышение глубины истории S3")
                }
                val next = readStable(session, nextId, MAX_MANIFEST)
                total += next.bytes.size
                if (total > MAX_CHAIN_TOTAL) {
                    throw IOException("История S3 слишком велика")
                }
                ancestors.add(next.bytes)
                val manifest = JSONObject(String(next.bytes, Charsets.UTF_8))
                nextId = parentId(manifest) ?: break
            }

            // Recheck ROOT and ETag AFTER the entire immutable ancestry was read.
            // A second matching body is required, not just an S3 LastModified.
            val finalHead = readStable(session, "", MAX_ROOT)
            if (first.meta != finalHead.meta ||
                !first.bytes.contentEquals(finalHead.bytes)) {
                throw IOException("Текущий S3 head изменился во время проверки")
            }
            // Rust Core knows the claimed genesis pin from the authenticated
            // invitation and validates *all* canonical V1 manifests.
            val proof = json(NativeBridge.verifyS3ReadProbe(
                session, first.bytes, ancestors.toTypedArray()
            ))
            if (!proof.optBoolean("bytesOnly", false) ||
                proof.optBoolean("remoteOwnerVerified", true) ||
                proof.optBoolean("synced", true)) {
                throw IOException("Недопустимый статус проверки S3")
            }
            val checkpoint = S3CheckpointProbe.inspect(
                session, first.bytes, ancestors.toTypedArray(), checkpoints
            )
            // The latest manifest contains immutable, typed shard references.
            val latest = JSONObject(String(ancestors.first(), Charsets.UTF_8))
            val refs = latest.getJSONArray("shards")
            if (refs.length() > MAX_PREVIEW_SHARDS) {
                throw IOException("S3 индекс превышает лимит предпросмотра")
            }
            val shardIds = HashSet<String>()
            var shardTotal = 0
            for (i in 0 until refs.length()) {
                val shardId = refs.getJSONObject(i).getString("shard_id")
                if (!shardIds.add(shardId)) {
                    throw IOException("Повторяющийся S3 shard")
                }
                val fetched = readStable(session, shardId, MAX_SHARD)
                shardTotal += fetched.bytes.size
                if (shardTotal > MAX_PREVIEW_SHARDS * MAX_SHARD) {
                    throw IOException("S3 индекс превышает лимит")
                }
                shards.add(fetched.bytes)
            }
            // The root must still be byte-identical after reading every shard.
            val lastRoot = readStable(session, "", MAX_ROOT)
            if (first.meta != lastRoot.meta ||
                !first.bytes.contentEquals(lastRoot.bytes)) {
                throw IOException("S3 head изменился при чтении индекса")
            }
            val index = json(NativeBridge.verifyS3IndexProbe(
                session, first.bytes, ancestors.first(), shards.toTypedArray()
            ))
            if (!index.optBoolean("bytesOnly", false) ||
                index.optBoolean("remoteOwnerVerified", true) ||
                index.optBoolean("synced", true) ||
                index.getInt("revisions") != proof.getInt("revisions")) {
                throw IOException("Недопустимый индекс S3")
            }
            val supported = index.optBoolean("metadataPreviewSupported", false)
            var checkedRevisions = 0
            var checkedBlobs = 0
            var checkedBlobBytes = 0L
            var blobsSupported = false
            var candidates = 0
            var needsReview = 0
            var candidateRefs = emptyList<S3ManualImport.Candidate>()
            var importedNoteId: String? = null
            var slots = S3LocalSlotProbe.Scan()
            var bindingScan = S3BindingProbe.Scan()
            if (supported) {
                val refs = index.getJSONArray("revisionIds")
                if (refs.length() != index.getInt("revisions") ||
                    refs.length() > MAX_REVISION_PREVIEW) {
                    throw IOException("Недопустимый список ревизий S3")
                }
                val revisionBytes = ArrayList<ByteArray>()
                val revisionIds = HashSet<String>()
                try {
                    for (i in 0 until refs.length()) {
                        val revisionId = refs.getString(i)
                        if (!revisionIds.add(revisionId)) {
                            throw IOException("Дублирующаяся ревизия S3")
                        }
                                revisionBytes.add(readStableRevision(session, revisionId).bytes)
                    }
                    val afterRevisions = readStable(session, "", MAX_ROOT)
                    if (first.meta != afterRevisions.meta ||
                        !first.bytes.contentEquals(afterRevisions.bytes)) {
                        throw IOException("S3 head изменился при чтении ревизий")
                    }
                    val metadata = json(NativeBridge.verifyS3RevisionsProbe(
                        session, first.bytes, revisionBytes.toTypedArray()
                    ))
                    if (!metadata.optBoolean("metadataChecked", false) ||
                        metadata.optBoolean("remoteOwnerVerified", true) ||
                        metadata.optBoolean("synced", true) ||
                        metadata.getInt("revisionsChecked") != index.getInt("revisions")) {
                        throw IOException("Метаданные ревизий S3 не подтверждены")
                    }
                    checkedRevisions = metadata.getInt("revisionsChecked")
                    blobsSupported = metadata.optBoolean("blobPreviewSupported", false)
                    if (blobsSupported) {
                        val allowed = metadata.getJSONArray("blobRefs")
                        if (allowed.length() > MAX_BLOB_COUNT) {
                            throw IOException("Превышен лимит S3-объектов")
                        }
                        val received = ArrayList<ByteArray>()
                        val seenHashes = HashSet<String>()
                        var totalBlobBytes = 0L
                        try {
                            for (i in 0 until allowed.length()) {
                                val item = allowed.getJSONObject(i)
                                val hash = item.getString("hash")
                                val length = item.getLong("bytes")
                                if (!Regex("[0-9a-f]{64}").matches(hash) ||
                                    !seenHashes.add(hash) ||
                                    length !in 0L..MAX_BLOB_BYTES.toLong()) {
                                    throw IOException("Недопустимая ссылка на S3 blob")
                                }
                                totalBlobBytes += length
                                if (totalBlobBytes > MAX_BLOB_TOTAL) {
                                    throw IOException("S3 blob превышает бюджет")
                                }
                                        val blob = readStableBlob(session, hash)
                                if (blob.meta.size != length) {
                                    throw IOException("Размер S3 blob не совпадает с ревизией")
                                }
                                received.add(blob.bytes)
                            }
                            val afterBlobs = readStable(session, "", MAX_ROOT)
                            if (first.meta != afterBlobs.meta ||
                                !first.bytes.contentEquals(afterBlobs.bytes)) {
                                throw IOException("S3 head изменился при чтении содержимого")
                            }
                            val proofBlobs = json(NativeBridge.verifyS3BlobsProbe(
                                session, first.bytes, received.toTypedArray()
                            ))
                            if (!proofBlobs.optBoolean("contentChecked", false) ||
                                proofBlobs.optBoolean("remoteOwnerVerified", true) ||
                                proofBlobs.optBoolean("synced", true) ||
                                proofBlobs.getInt("blobsChecked") != allowed.length() ||
                                proofBlobs.getLong("bytesChecked") != totalBlobBytes) {
                                throw IOException("S3 blob не прошёл проверку целостности")
                            }
                            val possible = proofBlobs.getInt("receiveCandidates")
                            val review = proofBlobs.getInt("needsReview")
                            val forked = proofBlobs.getInt("forkedDocuments")
                            val deleted = proofBlobs.getInt("deletedDocuments")
                            val unsupported = proofBlobs.getInt("unsupportedDocuments")
                            if (proofBlobs.optBoolean("localConflictsChecked", true) ||
                                proofBlobs.optBoolean("importAuthorized", true) ||
                                possible < 0 || review < 0 ||
                                forked < 0 || deleted < 0 || unsupported < 0 ||
                                forked + deleted + unsupported != review ||
                                possible + review > index.getInt("documents")) {
                                throw IOException("Недостоверный план импорта S3")
                            }
                            checkedBlobs = proofBlobs.getInt("blobsChecked")
                            checkedBlobBytes = proofBlobs.getLong("bytesChecked")
                            candidates = possible
                            needsReview = review
                            slots = S3LocalSlotProbe.inspect(session, first.bytes, possible)
                            bindingScan = S3BindingProbe.inspect(session, first.bytes, possible)
                            candidateRefs = S3ManualImport.parseCandidates(proofBlobs)
                            if (selectedDocument != null) {
                                if (checkpoint.relation != "unchanged" ||
                                    checkpoint.expectedPrior == null ||
                                    !slots.checked || !bindingScan.available) {
                                    throw IOException("Закрепите и повторно проверьте источник S3")
                                }
                                importedNoteId = S3ManualImport.importSelected(
                                    session, first.bytes, selectedDocument, candidateRefs, allowed, received
                                )
                            }
                        } finally {
                            firstClear(received)
                        }
                    }
                } finally {
                    firstClear(revisionBytes)
                }
            }
            if (selectedDocument != null && importedNoteId == null) {
                throw IOException("Выбранный Markdown не прошёл полную проверку S3")
            }
            return Report(
                proof.getLong("generation"), proof.getInt("manifestsChecked"),
                proof.getInt("revisions"), index.getInt("documents"),
                index.getInt("shardsChecked"),
                metadataRevisionsChecked = checkedRevisions,
                metadataPreviewSupported = supported,
                contentPreviewSupported = blobsSupported,
                verifiedBlobCount = checkedBlobs,
                verifiedBlobBytes = checkedBlobBytes,
                receiveCandidatesPreview = candidates,
                receiveNeedsReviewPreview = needsReview,
                manualImportCandidates = candidateRefs,
                importedLocalNoteId = importedNoteId,
                localSlotsChecked = slots.checked,
                localSlotsAbsent = slots.absent,
                localSlotsIdenticalUnbound = slots.identicalButUnbound,
                localSlotsConflicting = slots.conflicting,
                localSlotsUnsafe = slots.unsafe,
                bindingScan = bindingScan,
                checkpointRelation = checkpoint.relation,
                checkpointProposal = if (blobsSupported) checkpoint else null,
            )
        } finally {
            // Credentials are dropped in native memory even on timeout or UI
            // cancellation. We do not advance any durable sync checkpoint.
            try {
                NativeBridge.endS3ReadProbe(session)
            } finally {
                firstClear(ancestors)
                firstClear(shards)
            }
        }
    }

    private fun firstClear(objects: List<ByteArray>) {
        for (bytes in objects) bytes.fill(0)
    }
}
