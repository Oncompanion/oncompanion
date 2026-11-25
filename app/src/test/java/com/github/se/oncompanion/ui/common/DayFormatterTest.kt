// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.common

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Date headings follow changes to the display pattern and the app language. */
@RunWith(AndroidJUnit4::class)
class DayFormatterTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun headingUpdatesWhenThePatternOrLocaleChanges() {
    val day = LocalDate.of(2026, 10, 3)
    val pattern = mutableStateOf("EEEE d MMMM")
    val locale = mutableStateOf(Locale.ENGLISH)
    compose.setContent {
      val configuration =
          Configuration(LocalConfiguration.current).apply { setLocale(locale.value) }
      CompositionLocalProvider(LocalConfiguration provides configuration) {
        Text(rememberAppDateFormatter(pattern.value).formatDateHeading(day))
      }
    }

    compose.onNodeWithText("Saturday 3 October").assertExists()
    compose.runOnIdle { pattern.value = "MMMM yyyy" }
    compose.onNodeWithText("October 2026").assertExists()
    compose.runOnIdle { locale.value = Locale.FRENCH }
    compose.onNodeWithText("Octobre 2026").assertExists()
  }

  @Test
  fun headingStaysEmptyWhenOptionalDateFieldsAreMissing() {
    val optionalWeekday = DateTimeFormatter.ofPattern("[EEEE]", Locale.FRENCH)
    assertEquals("", optionalWeekday.formatDateHeading(YearMonth.of(2026, 10)))
  }
}
