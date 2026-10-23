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
    private const val MAX_CHAIN_TOTAL = 8 * 1024 * 1024
    private const val MAX_ANCESTORS = 128
    private val ETA = Regex("\"[A-Za-z0-9_.-]{1,128}\"")
    private val CLOCK = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    internal data class Report(
        val latestGeneration: Long,
        val manifestsChecked: Int,
        val latestRevisions: Int,
        val verifiedOnlyBytes: Boolean = true,
    )
    private data class Meta(val etag: String, val size: Long)
    private data class ObjectBytes(val meta: Meta, val bytes: ByteArray)

    private fun json(raw: String): JSONObject {
        val obj = JSONObject(raw)
        if (!obj.optBoolean("ok")) {
            throw IOException("S3 Core: " + obj.optString("code", "rejected"))
        }
        return obj.getJSONObject("data")
    }

    private fun utcStamp(): String = CLOCK.format(Instant.now())

    private fun execute(
        session: String, method: String, manifestId: String,
        ifMatch: String = "", maxBytes: Int,
    ): ObjectBytes {
        val signed = json(NativeBridge.signS3ReadProbe(
            session, method, manifestId, ifMatch, utcStamp()
        ))
        val target = URL(signed.getString("url"))
        // Even a compromised untrusted manifest cannot trigger a redirect or
        // a request outside the fixed Yandex S3 server.
        if (target.protocol != "https" ||
            target.host != "storage.yandexcloud.net" ||
            target.port != -1 || target.userInfo != null ||
            target.query != null || target.ref != null) {
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
            if (socket.responseCode != HttpURLConnection.HTTP_OK) {
                // 301/302, 403, 404, 412 and unknown statuses are all failures.
                throw IOException("S3 отказал в чтении (HTTP " + socket.responseCode + ")")
            }
            val etag = socket.getHeaderField("ETag") ?: ""
            if (!ETA.matches(etag)) {
                throw IOException("S3 вернул недопустимый ETag")
            }
            val contentEncoding = socket.getHeaderField("Content-Encoding")
            if (!contentEncoding.isNullOrEmpty() &&
                !contentEncoding.equals("identity", ignoreCase = true)) {
                throw IOException("Неожиданное кодирование S3-объекта")
            }
            val length = socket.contentLengthLong
            if (length < 1 || length > maxBytes.toLong()) {
                throw IOException("Недопустимый размер S3-объекта")
            }
            if (method == "HEAD") {
                return ObjectBytes(Meta(etag, length), ByteArray(0))
            }
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
            if (bytes.size.toLong() != length) {
                throw IOException("Неполная передача S3-объекта")
            }
            return ObjectBytes(Meta(etag, length), bytes)
        } finally {
            socket.disconnect()
        }
    }

    private fun readStable(session: String, manifestId: String, maxBytes: Int): ObjectBytes {
        val before = execute(session, "HEAD", manifestId, maxBytes = maxBytes).meta
        val body = execute(
            session, "GET", manifestId, ifMatch = before.etag, maxBytes = maxBytes
        )
        val after = execute(session, "HEAD", manifestId, maxBytes = maxBytes).meta
        if (before != after || body.meta != before ||
            body.bytes.size.toLong() != before.size) {
            throw IOException("S3-объект изменился при чтении")
        }
        return body
    }

    private fun parentId(json: JSONObject): String? {
        if (!json.has("previous_manifest_id") || json.isNull("previous_manifest_id")) {
            return null
        }
        val id = json.getString("previous_manifest_id")
        if (id.length != 36) throw IOException("Повреждена цепочка S3")
        return id
    }

    fun run(store: S3ProtectedInviteStore): Report {
        val start = store.open().use { invitation ->
            json(NativeBridge.beginS3ReadProbe(
                invitation.encryptedInvitation, invitation.password
            ))
        }
        val session = start.getString("session")
        val ancestors = ArrayList<ByteArray>()
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
            return Report(
                proof.getLong("generation"),
                proof.getInt("manifestsChecked"),
                proof.getInt("revisions"),
            )
        } finally {
            // Credentials are dropped in native memory even on timeout or UI
            // cancellation. We do not advance any durable sync checkpoint.
            try {
                NativeBridge.endS3ReadProbe(session)
            } finally {
                firstClear(ancestors)
            }
        }
    }

    private fun firstClear(objects: List<ByteArray>) {
        for (bytes in objects) bytes.fill(0)
    }
}
