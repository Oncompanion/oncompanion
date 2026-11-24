// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SymptomLabelsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val fatigue =
      SymptomEntry(types = listOf(SymptomType.FATIGUE), intensity = 3, occurredAt = Instant.EPOCH)

  @Test
  fun everyTypeHasItsOwnLabel() {
    val labels = SymptomType.entries.map { context.getString(it.label) }

    assertEquals(
        listOf(
            "Fatigue",
            "Nausea",
            "Pain",
            "Fever",
            "Headache",
            "Loss of appetite",
            "Sleep problems",
            "Breathlessness",
            "Other",
        ),
        labels,
    )
  }

  @Test
  fun everySeverityHasItsOwnLabel() {
    assertEquals(
        listOf("Mild", "Moderate", "Severe"),
        Severity.entries.map { context.getString(it.label) },
    )
  }

  @Test
  fun title_ofAListedType_isItsLabel() {
    composeTestRule.setContent { Text(fatigue.title()) }
    composeTestRule.onNodeWithText("Fatigue").assertExists()
  }

  @Test
  fun title_ofSeveralSymptoms_listsTheirLabelsInOrder() {
    composeTestRule.setContent {
      Text(fatigue.copy(types = listOf(SymptomType.HEADACHE, SymptomType.FATIGUE)).title())
    }
    composeTestRule.onNodeWithText("Headache, Fatigue").assertExists()
  }

  @Test
  fun title_ofOther_isItsName_orOtherWithoutOne() {
    val other = fatigue.copy(types = listOf(SymptomType.FATIGUE, SymptomType.OTHER))
    composeTestRule.setContent {
      Column {
        Text(other.copy(otherName = "Hiccups").title())
        Text(other.copy(notes = "Hiccups").title())
      }
    }
    composeTestRule.onNodeWithText("Fatigue, Hiccups").assertExists()
    composeTestRule.onNodeWithText("Fatigue, Other").assertExists()
  }

  @Test
  fun strengthQuestion_namesTheSymptom_withAreForAPlural() {
    val cases =
        listOf(
            Triple(SymptomType.FATIGUE, null, "How strong is your fatigue? (optional)"),
            Triple(
                SymptomType.SLEEP_PROBLEMS,
                null,
                "How strong are your sleep problems? (optional)",
            ),
            Triple(SymptomType.OTHER, "Hiccups", "How strong is your Hiccups? (optional)"),
            Triple(SymptomType.OTHER, null, "How strong is it? (optional)"),
        )
    var questions = emptyList<String>()
    composeTestRule.setContent {
      questions = cases.map { (type, otherName, _) -> strengthQuestion(type, otherName) }
    }
    composeTestRule.waitForIdle()
    assertEquals(cases.map { it.third }, questions)
  }

  @Test
  fun dayLabel_isTodayYesterday_orTheDay_withTheYearOnlyForAnotherYear() {
    val today = LocalDate.of(2026, 10, 5)
    val labels = mutableListOf<String>()
    composeTestRule.setContent {
      labels +=
          listOf(today, today.minusDays(1), LocalDate.of(2026, 10, 3), LocalDate.of(2025, 10, 4))
              .map { dayLabel(it, today) }
    }

    composeTestRule.runOnIdle {
      assertEquals(listOf("Today", "Yesterday", "Sat 3 Oct", "Sat 4 Oct 2025"), labels.take(4))
    }
  }

  @Test
  @Config(qualifiers = "fr-rCH")
  fun dateLabelUsesFrenchWeekdayAndMonthNames() {
    val saturday = LocalDate.of(2026, 10, 3)
    val today = saturday.plusDays(4)
    composeTestRule.setContent { Text(dayLabel(saturday, today)) }
    composeTestRule.onNodeWithText("sam. 3 oct.").assertExists()
  }
}
