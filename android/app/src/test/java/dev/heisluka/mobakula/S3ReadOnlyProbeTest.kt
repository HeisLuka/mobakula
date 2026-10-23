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
}
