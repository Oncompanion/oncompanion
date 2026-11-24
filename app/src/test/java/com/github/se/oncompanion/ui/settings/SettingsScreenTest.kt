// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Verifies the implemented categories and its observed current value. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun screenShowsTheSavedChoiceAndBothNavigationCallbacks() {
    val repo = FakeSettingsRepository(DeviceSettings(AppearancePreference.DARK))
    val vm = SettingsViewModel(repo)
    var backs = 0
    var appearances = 0
    var formatPages = 0
    compose.setContent {
      OncompanionTheme { SettingsScreen(vm, { backs++ }, { appearances++ }, { formatPages++ }) }
    }
    compose.onNodeWithText("Dark").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose.onNodeWithTag(C.Tag.settings_date_time).performScrollTo().performClick()
    assertEquals(1, formatPages)
    assertEquals(1, backs)
    assertEquals(1, appearances)
    compose.onNodeWithText("Notifications").assertDoesNotExist()
  }

  @Test
  fun appearanceScopeIsDirectlyBelowAppearance() {
    compose.setContent {
      OncompanionTheme { SettingsContent(SettingsUiState(DeviceSettings()), {}, {}, {}, {}) }
    }
    val appearance = compose.onNodeWithTag(C.Tag.settings_appearance).fetchSemanticsNode()
    val scope = compose.onNodeWithText("Appearance is saved on this phone.").fetchSemanticsNode()
    val dateTime = compose.onNodeWithTag(C.Tag.settings_date_time).fetchSemanticsNode()
    assertTrue(appearance.boundsInRoot.bottom <= scope.boundsInRoot.top)
    assertTrue(scope.boundsInRoot.bottom <= dateTime.boundsInRoot.top)
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
            onDateTime = {},
            language = AppLanguage.FRENCH,
        )
      }
    }

    compose
        .onNode(hasText("Français") and hasTestTag(C.Tag.settings_language))
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun textSizeTracksConfigurationAndOpensAndroidSettings() {
    val vm = SettingsViewModel(FakeSettingsRepository(DeviceSettings()))
    val fontScale = mutableFloatStateOf(1f)
    compose.setContent {
      val configuration =
          Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale.floatValue }
      CompositionLocalProvider(LocalConfiguration provides configuration) {
        OncompanionTheme { SettingsScreen(vm, {}, {}, {}) }
      }
    }
    val textSize = compose.onNodeWithTag(C.Tag.settings_text_size)
    textSize.performScrollTo().assertTextContains("100% · Phone setting")
    textSize.performClick()
    assertEquals(
        "android.settings.TEXT_READING_SETTINGS",
        shadowOf(compose.activity).nextStartedActivity.action,
    )
    compose.runOnIdle { fontScale.floatValue = 1.5f }
    textSize.performScrollTo().assertTextContains("150% · Phone setting")
  }

  @Test
  fun textSizeFallsBackToAccessibilitySettings() {
    val activity = compose.activity
    shadowOf(activity.application).checkActivities(true)
    val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    val handler =
        ResolveInfo().apply {
          activityInfo =
              ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "AccessibilitySettings"
              }
        }
    shadowOf(activity.packageManager).addResolveInfoForIntent(accessibility, handler)
    val vm = SettingsViewModel(FakeSettingsRepository(DeviceSettings()))
    compose.setContent { OncompanionTheme { SettingsScreen(vm, {}, {}, {}) } }

    compose.onNodeWithTag(C.Tag.settings_text_size).performScrollTo().performClick()

    assertEquals(accessibility.action, shadowOf(activity).nextStartedActivity.action)
  }

  @Test
  fun textSizeShowsFeedbackWhenSystemSettingsAreUnavailable() {
    val activity = compose.activity
    shadowOf(activity.application).checkActivities(true)
    val vm = SettingsViewModel(FakeSettingsRepository(DeviceSettings()))
    compose.setContent { OncompanionTheme { SettingsScreen(vm, {}, {}, {}) } }

    compose.onNodeWithTag(C.Tag.settings_text_size).performScrollTo().performClick()

    assertEquals(
        "Couldn't open your phone's text size settings.",
        ShadowToast.getTextOfLatestToast(),
    )
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeAt200PercentKeepsCategoryAndBackReachable() {
    compose.setContent {
      OncompanionTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
          SettingsContent(
              SettingsUiState(DeviceSettings()),
              {},
              {},
              {},
              onDateTime = {},
              textSizePercent = 200,
          )
        }
      }
    }
    compose.onNodeWithTag(C.Tag.settings_appearance).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.settings_text_size).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }
}
