package dev.heisluka.mobakula

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only provider panel contract. No invitation, credentials or S3 network. */
@RunWith(AndroidJUnit4::class)
class S3CloudProviderUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun yandexTileOpensOverlayWithoutClaimingSynchronization() {
        // Cloud is its own tab. Local import/export must not invoke S3.
        compose.onNodeWithTag("tab_files").performClick()
        assertTrue(compose.onAllNodesWithTag("cloud_yandex_tile")
            .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("tab_cloud").performClick()
        compose.onNodeWithTag("cloud_sources_screen").assertExists()
        compose.onNodeWithTag("cloud_yandex_tile")
            .performScrollTo().assertExists().performClick()
        compose.onNodeWithTag("cloud_s3_sheet").assertExists()
        compose.onNodeWithTag("cloud_s3_content").assertExists()
        compose.onNodeWithText(
            "Автоматической синхронизации нет.", substring = true
        ).assertExists()
        assertTrue(compose.onAllNodesWithText("Синхронизировано")
            .fetchSemanticsNodes().isEmpty())
    }
}
