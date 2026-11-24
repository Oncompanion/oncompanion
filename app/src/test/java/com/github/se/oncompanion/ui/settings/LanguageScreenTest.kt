// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the locale choice, selection feedback, and French resource rendering. */
@RunWith(AndroidJUnit4::class)
class LanguageScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun modelExposesTheSavedChoiceBeforeTheFirstResume() {
    val model = LanguageViewModel(FakeAppLocaleAdapter())

    assertEquals(AppLanguage.ENGLISH, model.uiState.value)
  }

  @Test
  fun pickerAppliesFrenchAndClearsTheOverrideWhenFollowingDevice() {
    val adapter = FakeAppLocaleAdapter()
    val model = LanguageViewModel(adapter)
    compose.setContent { OncompanionTheme { LanguageScreen({}, model) } }

    compose.onNodeWithTag(C.Tag.language_english).assertIsSelected()
    compose.onNodeWithTag(C.Tag.language_french).performClick().assertIsSelected()
    compose.runOnIdle { assertEquals(listOf(AppLanguage.FRENCH), adapter.applied) }
    compose.onNodeWithTag(C.Tag.language_device).performClick().assertIsSelected()
    compose.runOnIdle {
      assertEquals(listOf(AppLanguage.FRENCH, AppLanguage.FOLLOW_DEVICE), adapter.applied)
    }
  }

  @Test
  @Config(qualifiers = "en")
  fun choicesUseTheirOwnNamesAndReportTheSelectedOption() {
    val selected = mutableStateOf(AppLanguage.FOLLOW_DEVICE)
    var backs = 0
    compose.setContent {
      OncompanionTheme { LanguageContent(selected.value, { backs++ }) { selected.value = it } }
    }
    compose.onNodeWithTag(C.Tag.language_device).assertIsSelected()
    compose.onNodeWithText("English").assertIsDisplayed()
    compose.onNodeWithText("Français").performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.language_english).performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.language_device).performClick().assertIsSelected()
    compose.onNodeWithText("Preview").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    assertEquals(1, backs)
  }

  @Test
  @Config(qualifiers = "fr")
  fun frenchResourcesShowThePreviewAndKeepEndonyms() {
    compose.setContent { OncompanionTheme { LanguageContent(AppLanguage.FRENCH, {}) {} } }
    compose.onNodeWithText("Langue").assertIsDisplayed()
    compose.onNodeWithText("Suivre l’appareil").assertIsDisplayed()
    compose.onNodeWithText("English").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.language_french).assertIsSelected()
    compose.onNodeWithText("mercredi 18 novembre, 14:30").performScrollTo().assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "fr-rCH")
  fun followingAFrenchDeviceStillShowsTheLocalizedPreview() {
    compose.setContent { OncompanionTheme { LanguageContent(AppLanguage.FOLLOW_DEVICE, {}) {} } }

    compose.onNodeWithTag(C.Tag.language_device).assertIsSelected()
    compose.onNodeWithText("mercredi 18 novembre, 14:30").performScrollTo().assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeAt200PercentKeepsChoicesAndBackReachable() {
    compose.setContent {
      OncompanionTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
          LanguageContent(AppLanguage.FOLLOW_DEVICE, {}) {}
        }
      }
    }
    for (tag in listOf(C.Tag.language_device, C.Tag.language_english, C.Tag.language_french)) {
      compose.onNodeWithTag(tag).performScrollTo().assertIsFullyVisible()
    }
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }
}

private class FakeAppLocaleAdapter : AppLocaleAdapter {
  val applied = mutableListOf<AppLanguage>()

  override fun current() = AppLanguage.ENGLISH

  override fun apply(language: AppLanguage) {
    applied.add(language)
  }
}
