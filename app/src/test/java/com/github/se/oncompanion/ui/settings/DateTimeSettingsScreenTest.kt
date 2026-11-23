// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies date and time choices, previews, and recreation using device-local settings. */
@RunWith(AndroidJUnit4::class)
class DateTimeSettingsScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val previews = DateTimePreviews(Locale.UK, "d MMM y", deviceUses24Hours = true)

  @Test
  fun previewUsesTheConfirmedFormats() {
    compose.setContent {
      OncompanionTheme {
        DateTimeSettingsContent(
            SettingsUiState(
                DeviceSettings(
                    dateFormat = DateFormatPreference.DAY_MONTH_YEAR,
                    timeFormat = TimeFormatPreference.TWELVE_HOUR,
                )
            ),
            previews,
            {},
            {},
            {},
            {},
        )
      }
    }
    compose
        .onNodeWithTag(C.Tag.settings_format_preview)
        .performScrollTo()
        .assertTextEquals("18/11/2026, 2:30 pm")
  }

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE, fontScale = 2f)
  fun longerDateChoicesRemainReachableAt200Percent() {
    val repo = FakeSettingsRepository()
    val model = SettingsViewModel(repo)
    compose.setContent { OncompanionTheme { DateFormatScreen(model, {}) } }
    val last = compose.onNodeWithTag(C.Tag.settingsDateChoice("YEAR_MONTH_DAY"))
    last.performScrollTo().assertIsFullyVisible()
    last.performClick().assertIsSelected()
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }

  @Test
  fun timeSelectionSurvivesRecreationWithANewModel() {
    val repo = FakeSettingsRepository()
    val model = SettingsViewModel(repo)
    compose.setContent { OncompanionTheme { TimeFormatScreen(model, {}) } }
    compose.onNodeWithTag(C.Tag.settingsTimeChoice("TWELVE_HOUR")).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.settingsTimeChoice("TWELVE_HOUR")).assertIsSelected()

    compose.activityRule.scenario.recreate()
    val recreated = SettingsViewModel(repo)
    compose.activity.setContent { OncompanionTheme { TimeFormatScreen(recreated, {}) } }
    compose.onNodeWithTag(C.Tag.settingsTimeChoice("TWELVE_HOUR")).assertIsSelected()
  }

  @Test
  fun dateSelectionAndCombinedPreviewSurviveActivityRecreation() {
    val model =
        SettingsViewModel(
            FakeSettingsRepository(DeviceSettings(timeFormat = TimeFormatPreference.TWELVE_HOUR))
        )
    compose.setContent { OncompanionTheme { DateFormatScreen(model, {}) } }
    val choice = compose.onNodeWithTag(C.Tag.settingsDateChoice("YEAR_MONTH_DAY"))
    choice.performScrollTo().performClick()
    choice.assertIsSelected()

    compose.activityRule.scenario.recreate()
    compose.activity.setContent { OncompanionTheme { DateFormatScreen(model, {}) } }
    choice.performScrollTo().assertIsSelected()
    compose.activity.setContent {
      OncompanionTheme {
        val state by model.uiState.collectAsStateWithLifecycle()
        DateTimeSettingsContent(state, previews, {}, {}, {}, {})
      }
    }
    compose
        .onNodeWithTag(C.Tag.settings_format_preview)
        .performScrollTo()
        .assertTextEquals("2026-11-18, 2:30 pm")
  }

  @Test
  fun formatsUseTheDevicePatternButLocalizeWrittenMonths() {
    val french = DateTimePreviews(Locale.FRANCE, "MMM d, y", deviceUses24Hours = false)
    assertEquals("nov. 18, 2026", french.date(DateFormatPreference.FOLLOW_DEVICE))
    assertEquals("18 nov. 2026", french.date(DateFormatPreference.TEXT_DATE))
    assertEquals("18/11/2026", french.date(DateFormatPreference.DAY_MONTH_YEAR))
    assertEquals("2026-11-18", french.date(DateFormatPreference.YEAR_MONTH_DAY))
    assertEquals("14:30", previews.time(TimeFormatPreference.FOLLOW_DEVICE))
    assertEquals(
        "2:30 PM",
        DateTimePreviews(Locale.US, "MMM d, y", false).time(TimeFormatPreference.FOLLOW_DEVICE),
    )
    assertEquals("14:30", french.time(TimeFormatPreference.TWENTY_FOUR_HOUR))
    assertEquals(
        "2:30 PM",
        DateTimePreviews(Locale.US, "MMM d, y", true).time(TimeFormatPreference.TWELVE_HOUR),
    )
  }
}
