// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests of [PlanningList]. */
@RunWith(RobolectricTestRunner::class)
class PlanningListTest {
  @get:Rule val compose = createComposeRule()
  private val zone = ZoneId.of("Europe/Zurich")
  private val today = LocalDate.of(2026, 11, 17)
  private val window = PlanningListWindow.around(today)

  private var earlier = 0
  private var later = 0
  private var todayAsked = 0
  private val topDays = mutableListOf<LocalDate>()
  private val actions =
      PlanningListActions(
          onShowEarlier = { earlier++ },
          onShowLater = { later++ },
          onTopDayChanged = { topDays += it },
          onToday = { todayAsked++ },
      )

  private fun appointment(date: LocalDate, title: String = "Visit $date") =
      PlanningItem(
          PlanningSource.Appointment(date.toString()),
          PlanningTiming.Timed(date.atTime(10, 0).atZone(zone).toInstant()),
          title,
      )

  private fun medication(date: LocalDate, frequency: String) =
      PlanningItem(
          PlanningSource.Medication("p", "m"),
          PlanningTiming.DateOnly(date),
          "Ondansetron",
          frequency = frequency,
      )

  /** One appointment a day, from [from] to [to] included. */
  private fun everyDay(from: LocalDate, to: LocalDate) =
      generateSequence(from) { it.plusDays(1) }
          .takeWhile { it <= to }
          .associateWith { listOf(appointment(it)) }

  private fun lines(
      itemsByDay: Map<LocalDate, List<PlanningItem>>,
      shownWindow: PlanningListWindow = window,
  ) = planningListLines(itemsByDay, today, shownWindow)

  private fun setList(
      lines: List<PlanningListLine>,
      selected: LocalDate = today,
      shownWindow: PlanningListWindow = window,
      isLoading: Boolean = false,
      dark: Boolean = false,
  ) {
    compose.setContent {
      OncompanionTheme(darkTheme = dark) {
        PlanningList(lines, shownWindow, selected, today, isLoading, actions) { Entry(it) }
      }
    }
  }

  @androidx.compose.runtime.Composable
  private fun Entry(item: PlanningItem) {
    Text(
        item.title,
        Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(C.Tag.planningItem(item.key)),
    )
  }

  @Test
  fun showsEachDayTodayMonthsAndDoses() {
    val past = today.minusDays(1)
    val tomorrow = today.plusDays(1)
    val december = LocalDate.of(2026, 12, 1)
    setList(
        lines(
            mapOf(
                past to listOf(appointment(past, "Blood test")),
                tomorrow to listOf(medication(tomorrow, "3 times a day")),
                december to
                    listOf(
                        appointment(december, "Art workshop"),
                        medication(december, "Once a day"),
                    ),
            )
        )
    )

    compose.onNodeWithText("Mon 16 Nov").assertIsDisplayed()
    compose.onNodeWithText("Blood test").assertIsDisplayed()
    compose.onNodeWithText("Today").assertIsDisplayed()
    compose.onNodeWithText("Tuesday 17 November").assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.planningDoses(tomorrow.toString()), useUnmergedTree = true)
        .assertIsDisplayed()
    compose.onNodeWithText("3 doses", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithText("Ondansetron").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planningMonth("2026-12")).assertIsDisplayed()
    compose.onNodeWithText("December 2026").assertIsDisplayed()
    compose.onNodeWithText("1 dose", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithText("Art workshop").assertIsDisplayed()
  }

  @Test
  fun opensOnTheSelectedDayAndReportsTheDayAtTheTop() {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    val selected = today.plusWeeks(3)
    setList(all, selected)

    compose.waitForIdle()
    assertEquals(selected, topDays.first())
    compose.onNodeWithTag(C.Tag.planningDayLine(selected.toString())).assertIsDisplayed()

    val later = all.indexOfFirst { it.key == PlanningListLine.dayKey(today.plusWeeks(5)) }
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(later)
    compose.waitForIdle()
    assertEquals(today.plusWeeks(5), topDays.last())
  }

  @Test
  fun scrollingToAnEdgeLoadsMoreOnce() {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    // Opens near the top, without scrolling: nothing is asked
    setList(all, window.start.plusDays(10))
    compose.waitForIdle()
    assertEquals(0 to 0, earlier to later)

    compose.onNodeWithTag(C.Tag.planning_list).performTouchInput { swipeDown() }
    compose.onNodeWithTag(C.Tag.planning_list).performTouchInput { swipeDown() }
    compose.waitForIdle()
    assertEquals(1 to 0, earlier to later)

    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToIndex(
            all.indexOfFirst { it.key == PlanningListLine.dayKey(window.end.minusDays(12)) }
        )
    compose.onNodeWithTag(C.Tag.planning_list).performTouchInput { swipeUp() }
    compose.waitForIdle()
    assertEquals(1 to 1, earlier to later)
  }

  @Test
  fun loadingWaitsForThePendingRead() {
    setList(
        lines(everyDay(window.start, window.end.minusDays(1))),
        window.start.plusDays(10),
        isLoading = true,
    )

    compose.onNodeWithTag(C.Tag.planning_list).performTouchInput { swipeDown() }
    compose.onNodeWithTag(C.Tag.planning_list).performTouchInput { swipeDown() }
    compose.waitForIdle()

    assertEquals(0, earlier)
  }

  @Test
  fun daysAddedAboveKeepTheListInPlace() {
    var shown by mutableStateOf(lines(everyDay(today, today.plusWeeks(8))))
    lateinit var state: LazyListState
    compose.setContent {
      OncompanionTheme {
        state = androidx.compose.foundation.lazy.rememberLazyListState()
        PlanningList(shown, window, today.plusWeeks(2), today, false, actions, listState = state) {
          Entry(it)
        }
      }
    }
    compose.waitForIdle()
    val top = compose.runOnIdle { state.layoutInfo.visibleItemsInfo.first().key }

    shown = lines(everyDay(window.start, today.plusWeeks(8)))
    compose.waitForIdle()

    assertEquals(top, compose.runOnIdle { state.layoutInfo.visibleItemsInfo.first().key })
    compose.onNodeWithTag(C.Tag.planningDayLine(today.plusWeeks(2).toString())).assertIsDisplayed()
  }

  @Test
  fun todayChipBringsTodayBack() {
    setList(lines(everyDay(window.start, window.end.minusDays(1))), today.plusWeeks(6))

    compose.onNodeWithText("Today · Tue 17 Nov").assertIsDisplayed().performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.planningDayLine(today.toString())).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_today_chip).assertDoesNotExist()
    assertEquals(0, todayAsked)
  }

  @Test
  fun todayChipSelectsTodayWhenItIsntLoaded() {
    val far = PlanningListWindow.around(LocalDate.of(2027, 6, 1))
    var shown by mutableStateOf(lines(everyDay(far.start, far.end.minusDays(1)), far))
    var shownWindow by mutableStateOf(far)
    compose.setContent {
      OncompanionTheme {
        PlanningList(shown, shownWindow, LocalDate.of(2027, 6, 1), today, false, actions) {
          Entry(it)
        }
      }
    }

    compose.onNodeWithTag(C.Tag.planning_today_chip).performClick()
    assertEquals(1, todayAsked)
    // The ViewModel recenters the window on today
    shownWindow = window
    shown = lines(everyDay(window.start, window.end.minusDays(1)))
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.planningDayLine(today.toString())).assertIsDisplayed()
  }

  @Test
  fun opensOnTheSelectedDayOnceTheFirstReadAnswers() {
    var shown by mutableStateOf(lines(emptyMap()))
    var loading by mutableStateOf(true)
    val selected = today.plusWeeks(3)
    compose.setContent {
      OncompanionTheme {
        PlanningList(shown, window, selected, today, loading, actions) { Entry(it) }
      }
    }
    compose.waitForIdle()
    assertTrue(topDays.isEmpty())

    shown = lines(everyDay(window.start, window.end.minusDays(1)))
    loading = false
    compose.waitForIdle()

    assertEquals(listOf(selected), topDays)
    compose.onNodeWithTag(C.Tag.planningDayLine(selected.toString())).assertIsDisplayed()
  }

  @Test
  fun reportsNoDayWhileAReadIsPending() {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    var shown by mutableStateOf(all)
    var loading by mutableStateOf(false)
    val target = today.plusWeeks(4)
    compose.setContent {
      OncompanionTheme {
        PlanningList(shown, window, target, today, loading, actions) { Entry(it) }
      }
    }
    compose.waitForIdle()
    assertEquals(listOf(target), topDays)

    // Only today's line is kept while the read is pending
    shown = lines(emptyMap())
    loading = true
    compose.waitForIdle()
    assertEquals(listOf(target), topDays)

    shown = all
    loading = false
    compose.waitForIdle()
    assertEquals(target, topDays.last())
  }

  @Test
  fun opensOnADaySelectedElsewhere() {
    val far = LocalDate.of(2027, 6, 1)
    val farWindow = PlanningListWindow.around(far)
    var shown by mutableStateOf(lines(everyDay(window.start, window.end.minusDays(1))))
    var shownWindow by mutableStateOf(window)
    var loading by mutableStateOf(false)
    var selected by mutableStateOf(today)
    val screen =
        actions.copy(
            onTopDayChanged = {
              topDays += it
              selected = it
            }
        )
    compose.setContent {
      OncompanionTheme {
        PlanningList(shown, shownWindow, selected, today, loading, screen) { Entry(it) }
      }
    }
    compose.waitForIdle()

    selected = far
    shownWindow = farWindow
    shown = lines(emptyMap(), farWindow)
    loading = true
    compose.waitForIdle()
    shown = lines(everyDay(farWindow.start, farWindow.end.minusDays(1)), farWindow)
    loading = false
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.planningDayLine(far.toString())).assertIsDisplayed()
    assertEquals(far, topDays.last())
    assertEquals(far, selected)
  }

  @Test fun rotationReopensWhereTheUserWas() = checkReopensWhereTheUserWas(processDeath = false)

  @Test fun processDeathReopensWhereTheUserWas() = checkReopensWhereTheUserWas(processDeath = true)

  private fun checkReopensWhereTheUserWas(processDeath: Boolean) {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    var shown by mutableStateOf(all)
    var loading by mutableStateOf(false)
    // The ViewModel's selected day, saved, which follows the day at the top while it lives
    var selected by mutableStateOf(today)
    var alive = true
    val screen =
        actions.copy(
            onTopDayChanged = {
              topDays += it
              if (alive) selected = it
            }
        )
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      OncompanionTheme {
        PlanningList(shown, window, selected, today, loading, screen) { Entry(it) }
      }
    }
    val target = today.plusWeeks(4)
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToIndex(all.indexOfFirst { it.key == PlanningListLine.dayKey(target) })
    compose.waitForIdle()
    assertEquals(target, selected)

    if (processDeath) {
      // The process dies: the new ViewModel has read nothing yet
      alive = false
      shown = lines(emptyMap())
      loading = true
    }
    restoration.emulateSavedInstanceStateRestore()
    compose.waitForIdle()
    alive = true
    shown = all
    loading = false
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.planningDayLine(target.toString())).assertIsDisplayed()
    assertEquals(target, topDays.last())
    assertEquals(target, selected)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE, fontScale = 2f)
  fun fitsSidewaysAtALargeFontInLightAndDark() {
    val tomorrow = today.plusDays(1)
    val all =
        lines(
            everyDay(window.start, window.end.minusDays(1)) +
                (tomorrow to listOf(medication(tomorrow, "Twice a day")))
        )
    var dark by mutableStateOf(false)
    compose.setContent {
      OncompanionTheme(darkTheme = dark) {
        PlanningList(all, window, today.minusDays(1), today, false, actions) { Entry(it) }
      }
    }
    for (theme in listOf(false, true)) {
      dark = theme
      compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(0)
      compose
          .onNodeWithTag(C.Tag.planning_list)
          .performScrollToIndex(all.indexOfFirst { it.key == PlanningListLine.dayKey(tomorrow) })
      compose
          .onNodeWithTag(C.Tag.planningDoses(tomorrow.toString()), useUnmergedTree = true)
          .assertIsFullyVisible()
      compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(all.lastIndex)
      compose.onNodeWithTag(C.Tag.planning_today_chip).assertIsFullyVisible()
    }
  }

  @Test
  @Config(qualifiers = "w360dp-h740dp", fontScale = 2f)
  fun fitsInPortraitAtALargeFont() {
    val tomorrow = today.plusDays(1)
    val all =
        lines(
            everyDay(window.start, window.end.minusDays(1)) +
                (tomorrow to listOf(medication(tomorrow, "3 times a day")))
        )
    setList(all, today)

    compose.onNodeWithTag(C.Tag.planningDayLine(today.toString())).assertIsFullyVisible()
    compose.onNodeWithText("Tuesday 17 November").assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToIndex(all.indexOfFirst { it.key == PlanningListLine.dayKey(tomorrow) })
    compose
        .onNodeWithTag(C.Tag.planningDoses(tomorrow.toString()), useUnmergedTree = true)
        .assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(all.lastIndex)
    compose.onNodeWithTag(C.Tag.planning_today_chip).assertIsFullyVisible()
  }

  @Test
  fun aLateEchoOfAnEarlierReportedDayDoesntMoveTheList() {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    var selected by mutableStateOf(today)
    compose.setContent {
      OncompanionTheme { PlanningList(all, window, selected, today, false, actions) { Entry(it) } }
    }
    val first = today.plusWeeks(2)
    val second = today.plusWeeks(5)
    for (day in listOf(first, second)) {
      compose
          .onNodeWithTag(C.Tag.planning_list)
          .performScrollToIndex(all.indexOfFirst { it.key == PlanningListLine.dayKey(day) })
      compose.waitForIdle()
    }
    assertEquals(second, topDays.last())

    // The screen's echo of the first report arrives after the List moved on, as in a fling
    selected = first
    compose.waitForIdle()

    assertEquals(second, topDays.last())
    compose.onNodeWithTag(C.Tag.planningDayLine(second.toString())).assertIsDisplayed()
  }

  @Test
  fun opensOnALoadedDaySelectedElsewhere() {
    val all = lines(everyDay(window.start, window.end.minusDays(1)))
    var selected by mutableStateOf(today)
    compose.setContent {
      OncompanionTheme { PlanningList(all, window, selected, today, false, actions) { Entry(it) } }
    }
    compose.waitForIdle()

    // Selected elsewhere, inside the loaded days: no read, the List moves to it
    val later = today.plusWeeks(6)
    selected = later
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.planningDayLine(later.toString())).assertIsDisplayed()
    assertEquals(later, topDays.last())
  }
}
