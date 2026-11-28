// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.formatDateHeading
import com.github.se.oncompanion.ui.common.rememberAppDateFormatter
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the endless List asks of its screen.
 *
 * @property onShowEarlier load more days before the first one (`PlanningViewModel.showEarlier`)
 * @property onShowLater load more days after the last one (`PlanningViewModel.showLater`)
 * @property onTopDayChanged the day at the top of the List changed: it is the selected day
 * @property onToday today isn't loaded: select it, so its line can be scrolled to
 */
data class PlanningListActions(
    val onShowEarlier: () -> Unit,
    val onShowLater: () -> Unit,
    val onTopDayChanged: (LocalDate) -> Unit,
    val onToday: () -> Unit,
)

/** Within this many days of an edge of the loaded days, scrolling towards it loads more. */
private const val EDGE_DAYS = 7L

/** The medication icon beside a day's doses. */
private val DoseIconSize = 16.dp

/**
 * Planning's List as one endless scroll: each day with something planned under its line, a heading
 * between months, and a chip back to today while its line is off screen.
 *
 * It opens on [selectedDate] once the first read has answered, and again after a rotation or
 * process death: the screen keeps [selectedDate] equal to the day at the top, so the List reopens
 * where the user was, whatever lines a new ViewModel loads first. It also reopens on a selected day
 * it didn't report itself (a grid opening a far day, today chosen from far away). While a read is
 * pending, it reports no day: the lines at its top may belong to the days being left.
 *
 * Scrolling near an edge of [window] asks for more days once the last read has answered
 * ([isLoading] false); the List keeps its place when they are added above, since every line has a
 * stable key.
 *
 * @param lines what to show, from [planningListLines]
 * @param entry draws one entry under its day
 */
@Composable
internal fun PlanningList(
    lines: List<PlanningListLine>,
    window: PlanningListWindow,
    selectedDate: LocalDate,
    today: LocalDate,
    isLoading: Boolean,
    actions: PlanningListActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    entry: @Composable (PlanningItem) -> Unit,
) {
  val currentLines by rememberUpdatedState(lines)
  val currentActions by rememberUpdatedState(actions)
  val currentIsLoading by rememberUpdatedState(isLoading)
  val currentSelectedDate by rememberUpdatedState(selectedDate)
  // Opens on the selected day once its lines are read. Not saved: a saved index would point into
  // other lines after process death, while the selected day is saved by the ViewModel
  var positioned by remember { mutableStateOf(false) }
  // Days the List reported since it last opened on a day: the screen echoes them back, a frame or
  // more later, and that echo isn't a new choice
  val reportedDays = remember { mutableSetOf<LocalDate>() }
  LaunchedEffect(positioned, isLoading, lines.isNotEmpty()) {
    if (!positioned && !isLoading && lines.isNotEmpty()) {
      listState.scrollToItem(lines.firstIndexFrom(currentSelectedDate))
      reportedDays.clear()
      positioned = true
    }
  }
  // A selected day the List didn't report comes from elsewhere (a grid, today far away): open on it
  LaunchedEffect(selectedDate) {
    if (positioned && selectedDate !in reportedDays) positioned = false
  }
  // A read that drops the selected day's line (the window moved around it): open on it again once
  // it answers. A read that only adds days keeps it, so the List stays where it is
  LaunchedEffect(isLoading) {
    if (isLoading && currentLines.none { it.key == PlanningListLine.dayKey(currentSelectedDate) }) {
      positioned = false
    }
  }
  if (positioned) {
    // The day at the top is the selected day, once read: while a read is pending, the lines at
    // the top may belong to the days being left
    LaunchedEffect(listState) {
      snapshotFlow {
        if (currentIsLoading) null else currentLines.topDay(listState.firstVisibleItemIndex)
      }
          .filterNotNull()
          .distinctUntilChanged()
          .collect {
            reportedDays += it
            currentActions.onTopDayChanged(it)
          }
    }
    if (!isLoading) {
      // Once per window and side, while the user scrolls towards a loaded edge: weeks with nothing
      // planned don't keep loading by themselves
      LaunchedEffect(window.start) {
        snapshotFlow {
          listState.isScrollInProgress &&
              listState.lastScrolledBackward &&
              currentLines.nearStart(listState, window)
        }
            .first { it }
        currentActions.onShowEarlier()
      }
      LaunchedEffect(window.end) {
        snapshotFlow {
          listState.isScrollInProgress &&
              listState.lastScrolledForward &&
              currentLines.nearEnd(listState, window)
        }
            .first { it }
        currentActions.onShowLater()
      }
    }
  }
  Box(modifier) {
    LazyColumn(
        Modifier.fillMaxSize().testTag(C.Tag.planning_list),
        state = listState,
        contentPadding = contentPadding,
    ) {
      items(lines, key = { it.key }, contentType = { it.contentType }) { line ->
        when (line) {
          is PlanningListLine.Month -> MonthHeading(line)
          is PlanningListLine.Day -> DayLine(line)
          is PlanningListLine.Entry -> entry(line.item)
        }
      }
    }
    if (positioned) {
      TodayChip(
          lines,
          today,
          listState,
          currentActions.onToday,
          Modifier.align(Alignment.TopCenter),
      )
    }
  }
}

/** The kind of line, so the List reuses the layout of lines of the same kind. */
private val PlanningListLine.contentType: Int
  get() =
      when (this) {
        is PlanningListLine.Month -> 0
        is PlanningListLine.Day -> 1
        is PlanningListLine.Entry -> 2
      }

/** The index of [date]'s line, or of the first day after it, or the last line. */
private fun List<PlanningListLine>.firstIndexFrom(date: LocalDate): Int =
    indexOfFirst { it is PlanningListLine.Day && it.date >= date }.takeIf { it >= 0 }
        ?: lastIndex.coerceAtLeast(0)

/** The day of the first line from [index] that isn't a month heading. */
private fun List<PlanningListLine>.topDay(index: Int): LocalDate? =
    asSequence().drop(index).firstOrNull { it !is PlanningListLine.Month }?.date

/** The first line shown is the first one, or within [EDGE_DAYS] of [window]'s first day. */
private fun List<PlanningListLine>.nearStart(
    state: LazyListState,
    window: PlanningListWindow,
): Boolean {
  val first = getOrNull(state.firstVisibleItemIndex) ?: return false
  return state.firstVisibleItemIndex == 0 || first.date < window.start.plusDays(EDGE_DAYS)
}

/** The last line shown is the last one, or within [EDGE_DAYS] of [window]'s end. */
private fun List<PlanningListLine>.nearEnd(
    state: LazyListState,
    window: PlanningListWindow,
): Boolean {
  val index = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
  val last = getOrNull(index) ?: return false
  return index == lastIndex || last.date >= window.end.minusDays(EDGE_DAYS)
}

/** "December 2026", with a rule under it. */
@Composable
private fun MonthHeading(line: PlanningListLine.Month) {
  val formatter = rememberAppDateFormatter("MMMM yyyy")
  Column(
      Modifier.fillMaxWidth()
          .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xl, bottom = Spacing.sm)
          .testTag(C.Tag.planningMonth(line.month.toString()))
  ) {
    Text(
        formatter.formatDateHeading(line.month),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
    HorizontalDivider(
        Modifier.padding(top = Spacing.xs),
        thickness = DividerDefaults.Thickness * 2,
        color = MaterialTheme.colorScheme.outline,
    )
  }
}

/**
 * A day's line: "Mon 30 Nov", a rule, and its doses on other days than today; today is teal, with
 * its pill and full date, and past days are muted.
 */
@Composable
private fun DayLine(line: PlanningListLine.Day) {
  val color =
      when {
        line.isToday -> MaterialTheme.colorScheme.primary
        line.isPast -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  Row(
      Modifier.fillMaxWidth()
          .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
          .testTag(C.Tag.planningDayLine(line.date.toString()))
          .semantics(mergeDescendants = true) { heading() },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    if (line.isToday) TodayDate(line.date) else ShortDate(line.date, color)
    HorizontalDivider(
        Modifier.weight(1f),
        thickness = if (line.isToday) DividerDefaults.Thickness * 2 else DividerDefaults.Thickness,
        color = if (line.isToday) color else MaterialTheme.colorScheme.outlineVariant,
    )
    line.doses?.let { doses ->
      Row(
          Modifier.testTag(C.Tag.planningDoses(line.date.toString())),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        Icon(
            painterResource(R.drawable.ic_medication),
            contentDescription = null,
            modifier = Modifier.size(DoseIconSize),
            tint = color,
        )
        Text(
            pluralStringResource(R.plurals.planning_day_doses, doses, doses),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
      }
    }
  }
}

/** "Mon 30 Nov". */
@Composable
private fun ShortDate(date: LocalDate, color: Color) {
  Text(
      rememberAppDateFormatter("EEE d MMM").formatDateHeading(date),
      style = MaterialTheme.typography.labelLarge,
      color = color,
  )
}

/** The "Today" pill and "Tuesday 17 November", in teal. */
@Composable
private fun TodayDate(date: LocalDate) {
  Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
  ) {
    Text(
        stringResource(R.string.planning_today),
        Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        style = MaterialTheme.typography.labelMedium,
    )
  }
  Text(
      rememberAppDateFormatter("EEEE d MMMM").formatDateHeading(date),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
  )
}

/**
 * "↑ Today · Tue 17 Nov" while today's line is off screen, pointing to where it is; it scrolls back
 * to it, or selects today first when it isn't loaded.
 */
@Composable
private fun TodayChip(
    lines: List<PlanningListLine>,
    today: LocalDate,
    listState: LazyListState,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val todayKey = PlanningListLine.dayKey(today)
  val todayIndex = remember(lines, todayKey) { lines.indexOfFirst { it.key == todayKey } }
  val todayShown by
      remember(listState, todayKey) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == todayKey } }
      }
  val scope = rememberCoroutineScope()
  // Today wasn't loaded: scroll to it once its line arrives
  var scrollWhenLoaded by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(scrollWhenLoaded, todayIndex) {
    if (scrollWhenLoaded && todayIndex >= 0) {
      listState.scrollToItem(todayIndex)
      scrollWhenLoaded = false
    }
  }
  // Recomposes only when the arrow flips, not at each scrolled line
  val above by
      remember(lines, today, listState) {
        derivedStateOf {
          val topDay = lines.topDay(listState.firstVisibleItemIndex)
          topDay != null && today < topDay
        }
      }
  if (todayShown || lines.isEmpty()) return
  Surface(
      onClick = {
        if (todayIndex >= 0) scope.launch { listState.animateScrollToItem(todayIndex) }
        else {
          scrollWhenLoaded = true
          onToday()
        }
      },
      modifier =
          modifier
              .padding(top = Spacing.sm)
              .elevation1(MaterialTheme.shapes.extraLarge)
              .testTag(C.Tag.planning_today_chip),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
  ) {
    Row(
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
      Icon(
          if (above) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
          contentDescription = null,
      )
      Text(
          stringResource(
              R.string.planning_today_chip,
              rememberAppDateFormatter("EEE d MMM").formatDateHeading(today),
          ),
          style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}
