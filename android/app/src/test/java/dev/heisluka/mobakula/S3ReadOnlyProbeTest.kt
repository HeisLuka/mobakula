package dev.heisluka.mobakula

import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline policy tests: no S3 credentials or Android network required. */
class S3ReadOnlyProbeTest {
    @Test
    fun onlyFixedYandexTlsHostWithoutRedirectOrQueryIsAccepted() {
        assertTrue(S3ReadOnlyProbe.isPermittedEndpoint(
            URL("https://storage.yandexcloud.net/examplebucket/akula-sync/refs/current.json")
        ))
        listOf(
            "http://storage.yandexcloud.net/a",
            "https://storage.yandexcloud.net.evil.test/a",
            "https://user@storage.yandexcloud.net/a",
            "https://storage.yandexcloud.net:443/a",
            "https://storage.yandexcloud.net/a?x=1",
            "https://storage.yandexcloud.net/a#fragment",
            "https://127.0.0.1/a",
        ).forEach { url ->
            assertFalse("URL must be rejected: " + url,
                S3ReadOnlyProbe.isPermittedEndpoint(URL(url)))
        }
    }

    @Test
    fun quotedEtagAllowsSafeConditionalGetButRejectsUntrustedHeaders() {
        assertTrue(S3ReadOnlyProbe.isPermittedEtag("\"abc123-5\""))
        for (bad in listOf(
            "", "abc123", "W/\"abc123\"", "\"bad,etag\"",
            "\"etag\"\r\nx-leak: key", "\"\"", "\"bad path\"",
        )) {
            assertFalse("ETag must be rejected", S3ReadOnlyProbe.isPermittedEtag(bad))
        }
    }


    @Test
    fun httpStatusEtagEncodingAndBoundsAreAlwaysFailClosed() {
        val valid = S3ReadOnlyProbe.checkedMeta(200, "\"abc123-2\"", "identity", 20, 4096)
        org.junit.Assert.assertEquals("\"abc123-2\"", valid.etag)
        for (status in listOf(301, 302, 403, 404, 412, 500)) {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                S3ReadOnlyProbe.checkedMeta(status, "\"abc123\"", null, 20, 4096)
            }
        }
        for (bad in listOf("", "abc123", "W/\"abc\"", "\"bad\r\ninjection\"")) {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                S3ReadOnlyProbe.checkedMeta(200, bad, null, 20, 4096)
            }
        }
        for (length in listOf(-1L, 0L, 4097L)) {
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                S3ReadOnlyProbe.checkedMeta(200, "\"abc\"", null, length, 4096)
            }
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            S3ReadOnlyProbe.checkedMeta(200, "\"abc\"", "gzip", 20, 4096)
        }
    }

    @Test
    fun stableReadRequiresExactlyHeadConditionalGetHead() {
        val metadata = S3ReadOnlyProbe.Meta("\"aabbcc\"", 4L)
        val payload = byteArrayOf(1, 2, 3, 4)
        val calls = mutableListOf<String>()
        val response = S3ReadOnlyProbe.readStableFrom("test-session", "manifest", 4096) {
                session, method, id, match, limit ->
            org.junit.Assert.assertEquals("test-session", session)
            org.junit.Assert.assertEquals("manifest", id)
            org.junit.Assert.assertEquals(4096, limit)
            calls.add(method + ":" + match)
            S3ReadOnlyProbe.ObjectBytes(
                metadata, if (method == "GET") payload else byteArrayOf()
            )
        }
        org.junit.Assert.assertArrayEquals(payload, response.bytes)
        org.junit.Assert.assertEquals(
            listOf("HEAD:", "GET:\"aabbcc\"", "HEAD:"), calls
        )
    }

    @Test
    fun changedHeadAndTruncatedConditionalGetAreRejected() {
        val stable = S3ReadOnlyProbe.Meta("\"aabbcc\"", 4L)
        val changed = S3ReadOnlyProbe.Meta("\"ddeeff\"", 4L)
        var calls = 0
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            S3ReadOnlyProbe.readStableFrom("token", "", 4096) { _, method, _, _, _ ->
                calls += 1
                S3ReadOnlyProbe.ObjectBytes(
                    if (calls == 3) changed else stable,
                    if (method == "GET") byteArrayOf(1, 2, 3, 4) else byteArrayOf()
                )
            }
        }
        org.junit.Assert.assertEquals(3, calls)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            S3ReadOnlyProbe.readStableFrom("token", "", 4096) { _, method, _, _, _ ->
                S3ReadOnlyProbe.ObjectBytes(
                    stable, if (method == "GET") byteArrayOf(1, 2) else byteArrayOf()
                )
            }
        }
    }

}
