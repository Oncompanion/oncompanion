// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies date and time choices, previews, and recreation using device-local settings. */
@RunWith(AndroidJUnit4::class)
class DateTimeSettingsScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val previews = DateTimePreviews(Locale.UK, "d MMM y", deviceUses24Hours = true)

  @Test
  fun formatScreensShowFeedbackAndKeepTheSavedChoiceOnFailure() {
    val screens: List<Pair<String?, @Composable (SettingsUiState, () -> Unit) -> Unit>> =
        listOf(
            C.Tag.settingsTimeChoice("TWELVE_HOUR") to
                { state, retry ->
                  TimeFormatContent(state, previews, {}, {}, retry)
                },
            C.Tag.settingsDateChoice("YEAR_MONTH_DAY") to
                { state, retry ->
                  DateFormatContent(state, previews, {}, {}, retry)
                },
            null to { state, retry -> DateTimeSettingsContent(state, previews, {}, {}, {}, retry) },
        )
    var state by mutableStateOf(SettingsUiState())
    var screen by mutableStateOf(screens.first().second)
    var retries = 0
    compose.setContent { OncompanionTheme { screen(state) { retries++ } } }

    for ((choiceTag, content) in screens) {
      compose.runOnIdle {
        screen = content
        state = SettingsUiState()
      }
      compose.onNodeWithTag(C.Tag.settings_loading).assertIsDisplayed()

      compose.runOnIdle { state = SettingsUiState(readFailed = true) }
      compose.onNodeWithTag(C.Tag.settings_retry).assertIsDisplayed().performClick()

      compose.runOnIdle {
        state = SettingsUiState(DeviceSettings(), savePhase = SettingsSavePhase.SAVING)
      }
      val choice = choiceTag?.let { compose.onNodeWithTag(it) }
      choice?.performScrollTo()?.assertIsNotEnabled()

      compose.runOnIdle {
        state = SettingsUiState(DeviceSettings(), savePhase = SettingsSavePhase.FAILED)
      }
      compose.onNodeWithTag(C.Tag.settings_save_error).performScrollTo().assertIsDisplayed()
      choice?.performScrollTo()?.assertIsNotSelected()
    }
    compose.runOnIdle { assertEquals(screens.size, retries) }
  }

  @Test
  fun summariesReflectDefaultAndCustomChoices() {
    val cases =
        listOf(
            DeviceSettings() to
                listOf("Follow device", "Follow device · 14:30", "Follow device · 18 Nov 2026"),
            DeviceSettings(
                dateFormat = DateFormatPreference.DAY_MONTH_YEAR,
                timeFormat = TimeFormatPreference.TWELVE_HOUR,
            ) to listOf("12-hour · Day / month / year", "12-hour · 2:30 pm", "Day / month / year"),
        )
    var settings by mutableStateOf(DeviceSettings())
    var showSettings by mutableStateOf(true)
    compose.setContent {
      OncompanionTheme {
        if (showSettings) SettingsContent(SettingsUiState(settings), {}, {}, {}, {})
        else DateTimeSettingsContent(SettingsUiState(settings), previews, {}, {}, {}, {})
      }
    }

    for ((chosen, expected) in cases) {
      compose.runOnIdle {
        settings = chosen
        showSettings = true
      }
      assertSummary(C.Tag.settings_date_time, expected[0])
      compose.runOnIdle { showSettings = false }
      assertSummary(C.Tag.settings_time_format, expected[1])
      assertSummary(C.Tag.settings_date_format, expected[2])
    }
  }

  @Test
  fun timeChoiceShowsTheCurrentPhoneFormat() {
    compose.setContent {
      OncompanionTheme {
        TimeFormatContent(SettingsUiState(DeviceSettings()), previews, {}, {}, {})
      }
    }
    assertSummary(C.Tag.settingsTimeChoice("FOLLOW_DEVICE"), "14:30 · current phone format")
  }

  @Test
  fun followDeviceUsesThePhonesDateOrder() {
    var phone by mutableStateOf("")
    lateinit var currentPreviews: DateTimePreviews
    compose.setContent { key(phone) { currentPreviews = rememberDateTimePreviews() } }

    for ((language, expected) in listOf("en-rUS" to "Nov 18, 2026", "en-rGB" to "18 Nov 2026")) {
      compose.runOnIdle {
        RuntimeEnvironment.setQualifiers(language)
        phone = language
      }
      compose.runOnIdle {
        assertEquals(language, expected, currentPreviews.date(DateFormatPreference.FOLLOW_DEVICE))
      }
    }
  }

  @Test
  @Config(qualifiers = "en-rUS")
  fun followDeviceReadsTheClockAgainOnResume() {
    val resolver = compose.activity.contentResolver
    Settings.System.putString(resolver, Settings.System.TIME_12_24, "12")
    lateinit var currentPreviews: DateTimePreviews
    compose.setContent { currentPreviews = rememberDateTimePreviews() }
    compose.runOnIdle {
      assertEquals("2:30 PM", currentPreviews.time(TimeFormatPreference.FOLLOW_DEVICE))
    }

    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    Settings.System.putString(resolver, Settings.System.TIME_12_24, "24")
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.runOnIdle {
      assertEquals("14:30", currentPreviews.time(TimeFormatPreference.FOLLOW_DEVICE))
    }
  }

  private fun assertSummary(tag: String, expected: String) {
    compose
        .onNodeWithTag(tag, useUnmergedTree = true)
        .onChildren()
        .filterToOne(hasText(expected))
        .assertExists()
  }

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
