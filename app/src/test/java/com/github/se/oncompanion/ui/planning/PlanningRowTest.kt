// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.textLayout
import com.github.se.oncompanion.ui.theme.LakeColors
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.cardItem
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rows of the Planning list (Figma R3 · Parts and R4): status, layout, colors. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlanningRowTest {
  @get:Rule val compose = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val zone = ZoneId.of("Europe/Zurich")
  private val day = LocalDate.of(2026, 11, 17)

  private fun at(time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

  /** "In 2 h · Cabinet Rive", with the no-break spaces of the app's string. */
  private fun status(state: String, detail: String) =
      context.getString(R.string.planning_status_detail, state, detail)

  private fun until(time: String) = context.getString(R.string.planning_until, time)

  private val physio =
      PlanningItem(
          PlanningSource.Appointment("physio"),
          PlanningTiming.Timed(at("14:00"), at("15:00")),
          "Physiotherapy",
          "Cabinet Rive",
      )
  private val ondansetron =
      PlanningItem(
          PlanningSource.Medication("prescription", "ondansetron"),
          PlanningTiming.DateOnly(day),
          "Ondansetron",
          frequency = "3 times a day",
      )

  /** A long day: an appointment each hour, so the list scrolls, the last one at 18:00. */
  private val longDay =
      (8..18).map { hour ->
        physio.copy(
            source = PlanningSource.Appointment("a$hour"),
            timing = PlanningTiming.Timed(at("%02d:00".format(hour))),
        )
      }

  private var now by mutableStateOf(at("12:00"))
  private var dark by mutableStateOf(false)
  private var fontScale by mutableStateOf(1f)
  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private lateinit var lake: LakeColors
  private val opened = mutableListOf<PlanningItem>()

  /** The phone shows 24-hour times, as in the Figma; put back what it was after the test. */
  private val resolver = context.contentResolver
  private val clock12or24 = Settings.System.getString(resolver, Settings.System.TIME_12_24)

  @Before
  fun use24HourTime() {
    Settings.System.putString(resolver, Settings.System.TIME_12_24, "24")
  }

  @After
  fun restoreTimeFormat() {
    Settings.System.putString(resolver, Settings.System.TIME_12_24, clock12or24)
  }

  /** Shows [items] on [day] once; the tests then change the state above and look again. */
  private fun render(items: List<PlanningItem>) {
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale)
      ) {
        OncompanionTheme(darkTheme = dark) {
          colors = MaterialTheme.colorScheme
          typography = MaterialTheme.typography
          lake = MaterialTheme.lakeColors
          PlanningContent(
              PlanningUiState(
                  day,
                  untimedItems = items.filter { it.timing is PlanningTiming.DateOnly },
                  scheduledItems = items.filter { it.timing is PlanningTiming.Timed },
                  isLoading = false,
                  today = day,
              ),
              day,
              zone,
              PlanningActions(
                  {},
                  {},
                  {},
                  {},
                  {},
                  onAddAppointment = {},
                  onItemClick = { opened += it },
              ),
              now = now,
          )
        }
      }
    }
  }

  private fun row(item: PlanningItem) = compose.onNodeWithTag(C.Tag.planningItem(item.key))

  /** Held sideways, where "state · detail" fits on one line. */
  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun anAppointmentsRow_saysWhereItStands_andMovesOnWithTheClock() {
    render(listOf(physio))
    val cases =
        listOf(
            "12:00" to status("In 2 h", "Cabinet Rive"),
            "13:35" to status("In 25 min", "Cabinet Rive"),
            "14:20" to status("Happening now", until("15:00")),
            "15:00" to "Cabinet Rive",
        )
    for ((time, expected) in cases) {
      now = at(time)
      compose.waitForIdle()
      row(physio).assertTextEquals("Physiotherapy", expected)
      // Muted once over, as the R3 rows are
      compose
          .onNodeWithText("Physiotherapy", useUnmergedTree = true)
          .assertTextStyle(
              typography.cardItem,
              if (time == "15:00") colors.onSurfaceVariant else colors.onSurface,
          )
    }
    row(physio).assertHasClickAction().performClick()
    assertEquals(listOf(physio), opened)
  }

  @Test
  fun onAnotherDay_anAppointmentShowsItsDetailOnly_andAMedicationItsFrequency() {
    now = at("12:00").minusSeconds(86_400)
    render(listOf(ondansetron, physio))
    row(physio).assertTextEquals("Physiotherapy", "Cabinet Rive")
    row(ondansetron).assertTextEquals("Ondansetron", "Frequency: 3 times a day")
  }

  /** A 390 dp phone, where "Happening now · until 15:00" doesn't fit beside the time column. */
  @Test
  @Config(qualifiers = PHONE)
  fun onAPhone_theStateAndItsDetailTakeALineEach_withoutAHangingDot() {
    now = at("14:20")
    // Decided before drawing: the very first frame already has a line each, never the one line
    // with the "·" left hanging then corrected a frame later
    compose.mainClock.autoAdvance = false
    render(listOf(physio))
    compose.onNodeWithText("Happening now", useUnmergedTree = true).assertExists()
    compose.mainClock.autoAdvance = true
    row(physio).assertTextEquals("Physiotherapy", "Happening now", until("15:00"))
    // Two lines, no "·": the first leaves room for the live dot, the second starts at the edge
    val first = compose.onNodeWithText("Happening now", useUnmergedTree = true).fetchSemanticsNode()
    val second = compose.onNodeWithText(until("15:00"), useUnmergedTree = true).fetchSemanticsNode()
    val firstText = compose.onNodeWithText("Happening now", useUnmergedTree = true).textLayout()
    assertEquals(1, firstText.lineCount)
    assertTrue(second.boundsInRoot.top >= first.boundsInRoot.bottom)
    assertEquals(first.boundsInRoot.left, second.boundsInRoot.left)
    assertTrue(firstText.getHorizontalPosition(0, true) > 0f)
  }

  @Test
  fun atALargeFont_theTimeGoesAboveTheCard() {
    render(listOf(physio))
    for ((scale, above) in listOf(1f to false, 2f to true)) {
      fontScale = scale
      compose.waitForIdle()
      val time = compose.onNodeWithText("14:00", useUnmergedTree = true).fetchSemanticsNode()
      val card = row(physio).fetchSemanticsNode()
      assertEquals("at ${scale}x", above, time.boundsInRoot.bottom <= card.boundsInRoot.top)
      if (!above) assertTrue(time.boundsInRoot.right <= card.boundsInRoot.left)
    }
  }

  @Test
  fun inLightAndDark_theCardAndTheKindsDiscUseTheThemesColors() {
    render(listOf(physio))
    for (isDark in listOf(false, true)) {
      dark = isDark
      compose.waitForIdle()
      val theme = if (isDark) "dark" else "light"
      // Halfway down, clear of the rounded corners
      val middle =
          row(physio).fetchSemanticsNode().let {
            it.size.height / 2f / it.layoutInfo.density.density
          }
      assertSameColor("$theme card", lake.stone.card, row(physio).colorAt(4.dp, middle.dp))
      // The appointment's violet disc, beside its icon (12 dp padding, then the 36 dp disc)
      assertSameColor(
          "$theme disc",
          colors.tertiaryContainer,
          row(physio).colorAt(16.dp, middle.dp),
      )
    }
  }

  /** The last row of a long day, scrolled into view, is fully visible and clear of Add. */
  private fun assertTheLastRowScrollsClearOfAdd() {
    fontScale = 2f
    render(longDay)
    val last = longDay.last()
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(last.key)))
        // Then to the very end, as far as the list lets the user scroll
        .performTouchInput { repeat(3) { swipeUp() } }
    compose.waitForIdle()
    row(last).assertIsFullyVisible()
    val add = compose.onNodeWithTag(C.Tag.planning_add).fetchSemanticsNode().boundsInRoot
    assertFalse(row(last).fetchSemanticsNode().boundsInRoot.overlaps(add))
  }

  @Test fun at200Percent_theLastRowsChevronScrollsClearOfAdd() = assertTheLastRowScrollsClearOfAdd()

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun heldSideways_at200Percent_theLastRowsChevronScrollsClearOfAdd() =
      assertTheLastRowScrollsClearOfAdd()
}
