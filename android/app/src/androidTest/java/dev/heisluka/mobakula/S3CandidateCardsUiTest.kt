package dev.heisluka.mobakula

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Render verified candidates without accessing S3, JNI, or cloud credentials. */
@RunWith(AndroidJUnit4::class)
class S3CandidateCardsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun newDocumentsOnlyCanBeImported() {
        val fresh = S3ManualImport.Candidate(
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
            "bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee", "a".repeat(64), 110
        )
        val received = fresh.copy(
            documentId = "cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        )
        val result = S3ReadOnlyProbe.Report(
            latestGeneration = 1, manifestsChecked = 1, latestRevisions = 2,
            checkpointRelation = "unchanged",
            contentPreviewSupported = true,
            localSlotsChecked = true,
            bindingScan = S3BindingProbe.Scan(
                available = true, journalExists = true, unmapped = 1, exact = 1,
                states = mapOf(
                    fresh.documentId to S3BindingProbe.State.UNMAPPED,
                    received.documentId to S3BindingProbe.State.EXACT,
                ),
            ),
            manualImportCandidates = listOf(fresh, received),
        )
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                S3CandidateCards(result, enabled = true) { chosen = it.documentId }
            }
        }
        assertEquals(1, compose.onAllNodesWithText("Получить локальную копию")
            .fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("Уже получена", substring = true)
            .fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithText("Новые").performClick()
        assertTrue(compose.onAllNodesWithText("Уже получена", substring = true)
            .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Получить локальную копию").performClick()
        assertEquals(fresh.documentId, chosen)

        compose.onNodeWithText("Полученные / спорные").performClick()
        assertTrue(compose.onAllNodesWithText("Получить локальную копию")
            .fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Уже получена", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
    }
}
