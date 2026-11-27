// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.DeviceSettings
import com.github.se.oncompanion.model.settings.FakeSettingsRepository
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.ui.common.LocalDateTimeFormats
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.cancel
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies that a saved choice reaches the existing screen through the real root. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class AppAppearanceRootTest {
  @get:org.junit.Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun savedFormatsUpdateTheExistingScreen() {
    val first =
        DeviceSettings(
            dateFormat = DateFormatPreference.DAY_MONTH_YEAR,
            timeFormat = TimeFormatPreference.TWELVE_HOUR,
        )
    val repository = FakeSettingsRepository(first)
    val model = AppAppearanceViewModel(repository)
    var screen: Any? = null
    val date = LocalDate.of(2026, 3, 5)
    val time = LocalTime.of(14, 30)

    try {
      compose.setContent {
        AppAppearanceRoot(compose.activity, model) {
          screen = remember { Any() }
          val formats = checkNotNull(LocalDateTimeFormats.current)
          Text("${formats.date(date)} · ${formats.time(time)}")
        }
      }
      compose.onNodeWithText("05/03/2026 · 2:30 PM").assertExists()
      val initial = compose.runOnIdle { screen }
      compose.runOnIdle {
        repository.settings.value =
            first.copy(
                dateFormat = DateFormatPreference.YEAR_MONTH_DAY,
                timeFormat = TimeFormatPreference.TWENTY_FOUR_HOUR,
            )
      }

      compose.onNodeWithText("2026-03-05 · 14:30").assertExists()
      compose.runOnIdle { assertSame(initial, screen) }
    } finally {
      model.viewModelScope.cancel()
    }
  }
}
