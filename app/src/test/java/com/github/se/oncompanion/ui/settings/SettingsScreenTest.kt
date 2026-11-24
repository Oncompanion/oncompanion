// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies the only implemented category and its observed current value. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun screenShowsTheSavedChoiceAndBothNavigationCallbacks() {
    val repo = FakeSettingsRepository(DeviceSettings(AppearancePreference.DARK))
    val vm = SettingsViewModel(repo)
    var backs = 0
    var appearances = 0
    compose.setContent { OncompanionTheme { SettingsScreen(vm, { backs++ }, { appearances++ }) } }
    compose.onNodeWithText("Dark").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    assertEquals(1, backs)
    assertEquals(1, appearances)
    listOf("Date and time", "Notifications", "Text size").forEach {
      compose.onNodeWithText(it).assertDoesNotExist()
    }
  }

  @Test
  fun appearanceScopeIsBetweenAppearanceAndLanguage() {
    compose.setContent {
      OncompanionTheme { SettingsContent(SettingsUiState(DeviceSettings()), {}, {}, {}) }
    }
    val appearance = compose.onNodeWithTag(C.Tag.settings_appearance).fetchSemanticsNode()
    val scope = compose.onNodeWithText("Appearance is saved on this phone.").fetchSemanticsNode()
    val language = compose.onNodeWithTag(C.Tag.settings_language).fetchSemanticsNode()
    assertTrue(appearance.boundsInRoot.bottom <= scope.boundsInRoot.top)
    assertTrue(scope.boundsInRoot.bottom <= language.boundsInRoot.top)
  }

  @Test
  fun languageRowShowsTheFrenchChoice() {
    compose.setContent {
      OncompanionTheme {
        SettingsContent(
            SettingsUiState(DeviceSettings()),
            {},
            {},
            {},
            language = AppLanguage.FRENCH,
        )
      }
    }

    compose.onNode(hasText("Français") and hasTestTag(C.Tag.settings_language)).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeAt200PercentKeepsCategoryAndBackReachable() {
    compose.setContent {
      OncompanionTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
          SettingsContent(SettingsUiState(DeviceSettings()), {}, {}, {})
        }
      }
    }
    compose.onNodeWithTag(C.Tag.settings_appearance).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }
}
