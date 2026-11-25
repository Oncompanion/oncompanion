// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.ui.common.currentDay
import java.text.Collator
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Selected calendar day, the days loaded around it, and the agenda currently available.
 *
 * @property period the days of the chosen view around [selectedDate]; the grids load its range
 * @property listWindow the days the List loads around [selectedDate]: kept while it holds the day,
 *   else rebuilt around it; it only grows ([PlanningViewModel.showEarlier],
 *   [PlanningViewModel.showLater]) and isn't saved, so a restored day gets a new one
 * @property itemsByDay every loaded entry under the day it starts in the display zone, in agenda
 *   order; only days with entries are keys. An entry that goes past midnight is listed on its start
 *   day only: showing it on each day it covers is up to the view that needs it (#555)
 * @property untimedItems the selected day's medications, by name
 * @property scheduledItems the selected day's timed entries, by time
 * @property isLoading a read is pending. With [isLoadingMore], the entries of the days that stay
 *   loaded are shown while the others load (the List grew, or the view changed over the same days);
 *   without it, nothing is known yet (the first read, another account, or new days only)
 * @property isLoadingMore while [isLoading], whether the entries shown come from an answer for some
 *   of the days being read: the List keeps them with a small loader instead of its first load's
 * @property isNotShared in caregiver mode, the patient doesn't share their planning: no entries
 * @property canAdd whether adding an appointment is offered: always in the user's own planning, and
 *   in caregiver mode once the patient's planning shows and they let the caregiver add (US-41)
 */
data class PlanningUiState(
    val selectedDate: LocalDate,
    val period: PlanningPeriod = PlanningPeriod.of(PlanningView.LIST, selectedDate),
    val listWindow: PlanningListWindow = PlanningListWindow.around(selectedDate),
    val itemsByDay: Map<LocalDate, List<PlanningItem>> = emptyMap(),
    val untimedItems: List<PlanningItem> = emptyList(),
    val scheduledItems: List<PlanningItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasError: Boolean = false,
    val today: LocalDate = selectedDate,
    val isNotShared: Boolean = false,
    val canAdd: Boolean = true,
) {
  /** The complete agenda in section order, derived from the two displayed sections. */
  val items: List<PlanningItem>
    get() = untimedItems + scheduledItems

  /** The view the period belongs to. */
  val view: PlanningView
    get() = period.view

  /** The days loaded in [zoneId]: the [listWindow] in List, else the [period]'s range. */
  fun loadedRange(zoneId: ZoneId): PlanningRange =
      if (view == PlanningView.LIST) listWindow.range(zoneId) else period.range(zoneId)

  /** The Monday of the selected day's week, which the List view's strip shows. */
  val weekStart: LocalDate
    get() = PlanningPeriod.of(PlanningView.LIST, selectedDate).start

  /** Whether the previous period is within years 1 to 9999. */
  val canGoToPreviousPeriod: Boolean
    get() = period.moved(selectedDate, -1) != null

  /** Whether the next period is within years 1 to 9999. */
  val canGoToNextPeriod: Boolean
    get() = period.moved(selectedDate, 1) != null
}

/**
 * Owns calendar selection: the selected day, the period of the chosen view, and the List's window
 * of days; it loads the window in List and the period in the grids. Presentation and date labels
 * belong to the view.
 *
 * @param patientUid in caregiver mode, the patient whose planning is shown; null for the user's own
 */
class PlanningViewModel(
    private val observePlanning: ObserveCarePlanning = ObserveCarePlanning(),
    private val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val savedStateHandle: SavedStateHandle,
    private val patientUid: String? = null,
) : ViewModel() {
  private val today = LocalDate.now(clock.withZone(zoneId))
  private val initialSelection = restoreSelection()
  private val mutableState =
      MutableStateFlow(
          PlanningUiState(
              initialSelection.first,
              initialSelection.second,
              today = today,
              canAdd = patientUid == null,
          )
      )
  val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()
  private var observation: Job? = null
  // The days the entries on screen were last read for; null before any entries or after a change of
  // account or access
  private var answeredRange: PlanningRange? = null

  init {
    save(mutableState.value)
    // The observation follows account and access changes itself
    observePeriod()
  }

  /**
   * Selects a day. The period stays while it holds the day (three days don't move under a day
   * picked inside them); otherwise the view's period of that day is loaded. Likewise, the List's
   * window stays while it holds the day, else a new one is built around it.
   */
  fun selectDate(date: LocalDate) {
    require(date.year in 1..9999) { "Date must have a four-digit positive year" }
    val period = mutableState.value.period
    select(date, period.takeIf { date in it } ?: PlanningPeriod.of(period.view, date))
  }

  /** Shows [view], keeping the selected day; three days then start on it. */
  fun selectView(view: PlanningView) {
    val state = mutableState.value
    if (view != state.view) select(state.selectedDate, PlanningPeriod.of(view, state.selectedDate))
  }

  /**
   * Loads four more weeks before the List's window; the List calls it near its first day. Nothing
   * happens in the grids, which load their period.
   */
  fun showEarlier() = growListWindow(PlanningListWindow::earlier)

  /**
   * Loads four more weeks after the List's window; the List calls it near its last day. Nothing
   * happens in the grids, which load their period.
   */
  fun showLater() = growListWindow(PlanningListWindow::later)

  /** Moves to the previous period, if it is within the supported years. */
  fun previousPeriod() = move(-1)

  /** Moves to the next period, if it is within the supported years. */
  fun nextPeriod() = move(1)

  /** Updates the today marker without changing the selected day or restarting observation. */
  fun refreshToday() {
    mutableState.value = mutableState.value.copy(today = LocalDate.now(clock.withZone(zoneId)))
  }

  /**
   * Keeps the today marker right while Planning is open: updates it now, then at each midnight,
   * until the returned job is cancelled. Like [refreshToday], it keeps the selected day. The screen
   * runs it while it is shown.
   */
  fun followToday(): Job = viewModelScope.launch {
    currentDay(clock.withZone(zoneId)).collect { today ->
      mutableState.value = mutableState.value.copy(today = today)
    }
  }

  /** Selects the current day, including after the screen has crossed midnight. */
  fun goToToday() {
    refreshToday()
    selectDate(mutableState.value.today)
  }

  /** Retries observation while keeping previously displayed items. */
  fun retry() = observePeriod()

  /**
   * The selected day and period saved before the activity was recreated, as a whole: if any part
   * can't be used, List and today. A save from the previous version, with the date only, shows that
   * date in List.
   */
  private fun restoreSelection(): Pair<LocalDate, PlanningPeriod> {
    val fallback = today to PlanningPeriod.of(PlanningView.LIST, today)
    val date =
        savedStateHandle.get<String>(SELECTED_DATE)?.let {
          runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf(PlanningDates::isSupported)
        } ?: return fallback
    val view = savedStateHandle.get<String>(VIEW)
    val start = savedStateHandle.get<String>(PERIOD_START)
    if (view == null && start == null) return date to PlanningPeriod.of(PlanningView.LIST, date)
    val period =
        if (view == null || start == null) null
        else
            runCatching { PlanningPeriod(PlanningView.valueOf(view), LocalDate.parse(start)) }
                .getOrNull()
    return if (period != null && date in period) date to period else fallback
  }

  private fun move(steps: Int) {
    val state = mutableState.value
    state.period.moved(state.selectedDate, steps)?.let { select(it.selectedDate, it.period) }
  }

  private fun growListWindow(grow: (PlanningListWindow) -> PlanningListWindow) {
    val state = mutableState.value
    if (state.view == PlanningView.LIST) show(state.copy(listWindow = grow(state.listWindow)))
  }

  /** Selects [date] in [period]; the List's window stays while it holds the day. */
  private fun select(date: LocalDate, period: PlanningPeriod) {
    val previous = mutableState.value
    val window = previous.listWindow.takeIf { date in it } ?: PlanningListWindow.around(date)
    show(previous.copy(selectedDate = date, period = period, listWindow = window))
  }

  /** Shows [next], loading again only when the days to load change. */
  private fun show(next: PlanningUiState) {
    save(next)
    val previousRange = mutableState.value.loadedRange(zoneId)
    val range = next.loadedRange(zoneId)
    // Days that are still loaded don't need a new read. Either way the new selection is published
    // once, with its own agenda
    if (range == previousRange) mutableState.value = next.withDayAgenda() else observePeriod(next)
  }

  private fun save(state: PlanningUiState) {
    savedStateHandle[SELECTED_DATE] = state.selectedDate.toString()
    savedStateHandle[VIEW] = state.view.name
    savedStateHandle[PERIOD_START] = state.period.start.toString()
  }

  /**
   * Observes the days [from] loads, published at once as loading. The entries of the days that stay
   * loaded are kept until the first answer (a retry, a wider window, a grid within the List's
   * days).
   */
  private fun observePeriod(from: PlanningUiState = mutableState.value) {
    observation?.cancel()
    val range = from.loadedRange(zoneId)
    mutableState.value =
        from
            .copy(
                isLoading = true,
                isLoadingMore = answeredRange?.overlaps(range) == true,
                hasError = false,
                itemsByDay = from.itemsByDay.filterKeys { it in range },
            )
            .withDayAgenda()
    // The kept items stay until the first answer, whatever it is; after that, an account change
    // clears them again
    var keepItems = true
    observation = viewModelScope.launch {
      try {
        observePlanning(patientUid, range).collect { planning ->
          // A failure keeps what is shown, like a retry until new entries arrive
          val itemsByDay =
              if (
                  planning !is CarePlanning.Failed &&
                      (planning !is CarePlanning.Loading || !keepItems)
              ) {
                (planning as? CarePlanning.Shown)
                    ?.items
                    ?.filter { it.timing in range }
                    .orEmpty()
                    .groupedByDay(zoneId)
              } else null
          val isLoadingMore = planning is CarePlanning.Loading && keepItems
          trackAnswer(planning, range, keepItems)
          if (planning !is CarePlanning.Loading) keepItems = false
          // The entries, the flags and the selected day's agenda are published together
          val state = mutableState.value
          mutableState.value =
              state
                  .copy(
                      itemsByDay = itemsByDay ?: state.itemsByDay,
                      isLoading = planning is CarePlanning.Loading,
                      isLoadingMore = isLoadingMore && state.isLoadingMore,
                      hasError = planning is CarePlanning.Failed,
                      isNotShared = planning is CarePlanning.NotShared,
                      // A caregiver only gets "Add" with the patient's planning on screen
                      canAdd =
                          patientUid == null || (planning as? CarePlanning.Shown)?.canAdd == true,
                  )
                  .withDayAgenda()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value =
            mutableState.value.copy(isLoading = false, isLoadingMore = false, hasError = true)
      }
    }
  }

  /**
   * Remembers which days the entries on screen were read for: a read of other days shows them as
   * loading more ([PlanningUiState.isLoadingMore]) only over those days. A failure keeps them.
   */
  private fun trackAnswer(planning: CarePlanning, range: PlanningRange, keepItems: Boolean) {
    when (planning) {
      is CarePlanning.Shown -> answeredRange = range
      CarePlanning.Failed -> Unit
      // An account or access change after the first answer clears the entries
      CarePlanning.Loading -> if (!keepItems) answeredRange = null
      CarePlanning.NotShared,
      CarePlanning.NoAccess -> answeredRange = null
    }
  }

  private companion object {
    const val SELECTED_DATE = "planning_selected_date"
    const val VIEW = "planning_view"
    const val PERIOD_START = "planning_period_start"
  }
}

/** Whether [this] and [other] have a day in common. */
private fun PlanningRange.overlaps(other: PlanningRange): Boolean =
    startDateInclusive < other.endDateExclusive && other.startDateInclusive < endDateExclusive

/**
 * [this] with the selected day's entries, already in agenda order in [itemsByDay], as its lists.
 */
private fun PlanningUiState.withDayAgenda(): PlanningUiState {
  val (untimed, scheduled) =
      itemsByDay[selectedDate].orEmpty().partition { it.timing is PlanningTiming.DateOnly }
  return copy(untimedItems = untimed, scheduledItems = scheduled)
}

/**
 * [this] grouped under the day each entry starts in [zoneId], days in order, each in agenda order:
 * medications first by name in the phone's locale (case and accents included), then timed entries
 * by time; the key keeps entries with the same name or time in a stable order. Medications keep
 * their calendar day in any zone. An entry is listed on its start day only.
 */
internal fun List<PlanningItem>.groupedByDay(zoneId: ZoneId): Map<LocalDate, List<PlanningItem>> {
  val titles = Collator.getInstance()
  val agendaOrder =
      compareBy<PlanningItem> { it.timing is PlanningTiming.Timed }
          .thenBy { (it.timing as? PlanningTiming.Timed)?.instant }
          .thenBy(titles) { if (it.timing is PlanningTiming.DateOnly) it.title else "" }
          .thenBy { it.key }
  return groupBy { it.timing.dateIn(zoneId) }
      .toSortedMap()
      .mapValues { (_, items) -> items.sortedWith(agendaOrder) }
}
