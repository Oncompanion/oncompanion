// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.common.currentTime
import com.github.se.oncompanion.ui.common.formatDateHeading
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.navigation.BottomBarUiState
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.reminder.ReminderPermissionViewModel
import com.github.se.oncompanion.ui.reminder.rememberReminderPermissionNotice
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Calendar and agenda events supplied by the screen or preview. */
data class PlanningActions(
    val onDateSelected: (LocalDate) -> Unit,
    val onPreviousPeriod: () -> Unit,
    val onNextPeriod: () -> Unit,
    val onToday: () -> Unit,
    val onRetry: () -> Unit,
    val onAddAppointment: ((LocalDate) -> Unit)? = null,
    val onItemClick: ((PlanningItem) -> Unit)? = null,
    val canOpenItem: (PlanningItem) -> Boolean = { true },
)

/**
 * The Planning tab, collecting calendar state and reusing the app's bottom navigation.
 *
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 * @param bottomBar what the bottom bar shows on every tab: its tabs and the Prescriptions badge
 */
@Composable
fun PlanningScreen(
    navigationActions: NavigationActions,
    viewModel: PlanningViewModel,
    modifier: Modifier = Modifier,
    onAddAppointment: ((LocalDate) -> Unit)? = null,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenMedication: (String, String) -> Unit,
    caregiverOf: String? = null,
    reminderPermissions: ReminderPermissionViewModel? = null,
    onReminderAccessChanged: () -> Unit = {},
    bottomBar: BottomBarUiState = BottomBarUiState(),
) {
  val reminderNotice = reminderPermissions?.let {
    rememberReminderPermissionNotice(it, onAccessChanged = onReminderAccessChanged)
  }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // One clock for every row: their status moves on at each new minute while Planning is shown
  val now by
      remember(viewModel) { currentTime(viewModel.clock) }
          .collectAsStateWithLifecycle(
              initialValue = remember(viewModel) { viewModel.clock.instant() }
          )
  val openItem: (PlanningItem) -> Unit = {
    openPlanningItem(it, onOpenAppointment, onOpenEvent, onOpenMedication)
  }
  // Keeps the today marker right while Planning is on screen: refreshes when it comes back, then
  // at each midnight
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  PlanningContent(
      state = state,
      today = state.today,
      zoneId = viewModel.zoneId,
      now = now,
      actions =
          PlanningActions(
              viewModel::selectDate,
              viewModel::previousPeriod,
              viewModel::nextPeriod,
              viewModel::goToToday,
              viewModel::retry,
              onAddAppointment = onAddAppointment,
              onItemClick = openItem,
          ),
      modifier = modifier,
      bottomBar = {
        BottomNavigationBar(
            Tab.PLANNING,
            { navigationActions.navigateToTab(it.route) },
            state = bottomBar,
        )
      },
      caregiverOf = caregiverOf,
      reminderNotice = reminderNotice,
  )
}

/** Opens the page of the feature that owns [item], preserving its source identifiers. */
internal fun openPlanningItem(
    item: PlanningItem,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenMedication: (String, String) -> Unit,
) {
  when (val source = item.source) {
    is PlanningSource.Appointment -> onOpenAppointment(source.appointmentId)
    is PlanningSource.Event -> onOpenEvent(source.eventId)
    is PlanningSource.Medication -> onOpenMedication(source.prescriptionId, source.medicationId)
  }
}

/**
 * Stateless calendar and agenda; unavailable appointment actions are omitted.
 *
 * @param now the time the rows' status is worked out at: the screen's clock, a fixed time in tests
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun PlanningContent(
    state: PlanningUiState,
    today: LocalDate,
    zoneId: ZoneId,
    actions: PlanningActions,
    modifier: Modifier = Modifier,
    now: Instant,
    bottomBar: @Composable () -> Unit = {},
    caregiverOf: String? = null,
    reminderNotice: (@Composable () -> Unit)? = null,
) {
  val locale = LocalConfiguration.current.locales[0]
  val compact = isCompactHeight()
  val showAdd = state.canAdd && actions.onAddAppointment != null
  // The Add button grows with the font: the list ends that far below it, so its last row can scroll
  // clear of the button (a floating action never covers another one)
  val density = LocalDensity.current
  var addHeight by remember { mutableStateOf(0.dp) }
  val listEnd = if (showAdd) addHeight + Spacing.lg * 2 else Spacing.lg
  Scaffold(
      modifier = modifier.fillMaxSize().testTag(C.Tag.planning_screen),
      bottomBar = bottomBar,
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      // On short screens, keep the floating action clear of the scrolling calendar and agenda.
      Column(Modifier.padding(end = if (compact && showAdd) 88.dp else 0.dp)) {
        if (!compact) PlanningHeader(state, today, locale, actions)
        PlanningAgenda(
            state,
            zoneId,
            locale,
            actions.onRetry,
            actions.onItemClick,
            Modifier.weight(1f),
            now = now,
            listEnd = listEnd,
            canOpenItem = actions.canOpenItem,
            header =
                if (compact || reminderNotice != null) {
                  {
                    if (compact) PlanningHeader(state, today, locale, actions)
                    reminderNotice?.invoke()
                  }
                } else null,
            caregiverOf = caregiverOf,
        )
      }
      actions.onAddAppointment?.let { onAdd ->
        // A caregiver only gets "+" when the patient lets them add appointments (US-41)
        if (!showAdd) return@let
        val addLabel = stringResource(R.string.planning_add)
        FloatingActionButton(
            onClick = { onAdd(state.selectedDate) },
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .onSizeChanged { addHeight = with(density) { it.height.toDp() } }
                    .testTag(C.Tag.planning_add)
                    .semantics { contentDescription = addLabel },
        ) {
          Text("+", style = MaterialTheme.typography.headlineMedium)
        }
      }
    }
  }
}

/** Shared calendar controls stay fixed in portrait and join the agenda on short windows. */
@Composable
private fun PlanningHeader(
    state: PlanningUiState,
    today: LocalDate,
    locale: Locale,
    actions: PlanningActions,
) {
  val fullDate = PlanningDates.format(state.selectedDate)
  Column {
    Text(
        stringResource(R.string.planning_title),
        Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp),
        style = MaterialTheme.typography.headlineMedium,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          DateTimeFormatter.ofPattern("MMMM yyyy", locale).formatDateHeading(state.selectedDate),
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
      )
      val previousLabel = stringResource(R.string.planning_previous_week)
      TextButton(
          actions.onPreviousPeriod,
          Modifier.testTag(C.Tag.planning_previous).semantics {
            contentDescription = previousLabel
          },
          enabled = state.canGoToPreviousPeriod,
      ) {
        Text("‹")
      }
      val todayLabel = stringResource(R.string.planning_go_today)
      TextButton(
          actions.onToday,
          Modifier.testTag(C.Tag.planning_today).semantics { contentDescription = todayLabel },
      ) {
        Text(stringResource(R.string.planning_today))
      }
      val nextLabel = stringResource(R.string.planning_next_week)
      TextButton(
          actions.onNextPeriod,
          Modifier.testTag(C.Tag.planning_next).semantics { contentDescription = nextLabel },
          enabled = state.canGoToNextPeriod,
      ) {
        Text("›")
      }
    }
    WeekStrip(
        state.weekStart,
        state.selectedDate,
        today,
        locale,
        actions.onDateSelected,
    )
    Text(
        if (state.selectedDate == today) stringResource(R.string.planning_today_date, fullDate)
        else fullDate,
        Modifier.padding(16.dp).testTag(C.Tag.planning_heading),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
internal fun WeekStrip(
    weekStart: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onDateSelected: (LocalDate) -> Unit,
) {
  Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(C.Tag.planning_week),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    repeat(7) { offset ->
      val date = weekStart.plusDays(offset.toLong())
      WeekDay(date, selectedDate, today, locale, onDateSelected, Modifier.weight(1f))
    }
  }
}

@Composable
private fun WeekDay(
    date: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
  val selected = date == selectedDate
  val supported = PlanningDates.isSupported(date)
  val unavailableLabel = stringResource(R.string.planning_date_unavailable)
  val todayText = stringResource(R.string.planning_today)
  Surface(
      color =
          if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
      contentColor =
          if (selected) MaterialTheme.colorScheme.onPrimary
          else MaterialTheme.colorScheme.onSurface,
      shape = RoundedCornerShape(24.dp),
      border =
          if (date == today && !selected)
              androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
          else null,
      modifier =
          modifier
              .testTag(C.Tag.planningDay(date.toString()))
              .selectable(
                  selected,
                  enabled = supported,
                  role = Role.Tab,
                  onClick = { onDateSelected(date) },
              )
              .semantics(mergeDescendants = true) {
                contentDescription =
                    dayDescription(date, today, supported, todayText, unavailableLabel)
              },
  ) {
    Column(
        Modifier.padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          if (supported) String.format(Locale.ROOT, "%02d", date.dayOfMonth) else "—",
          style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}

private fun dayDescription(
    date: LocalDate,
    today: LocalDate,
    supported: Boolean,
    todayText: String,
    unavailableLabel: String,
): String {
  if (!supported) return unavailableLabel
  val formattedDate = PlanningDates.format(date)
  return if (date == today) "$formattedDate, $todayText" else formattedDate
}

@Composable
internal fun PlanningAgenda(
    state: PlanningUiState,
    zoneId: ZoneId,
    locale: Locale,
    onRetry: () -> Unit,
    onItemClick: ((PlanningItem) -> Unit)?,
    modifier: Modifier = Modifier,
    now: Instant,
    listEnd: Dp = 96.dp,
    canAdd: Boolean = false,
    canOpenItem: (PlanningItem) -> Boolean = { true },
    header: (@Composable () -> Unit)? = null,
    caregiverOf: String? = null,
) {
  // Save scrolling with the tab, but start at the top when a different day is selected.
  val listState =
      rememberSaveable(state.selectedDate.toString(), saver = LazyListState.Saver) {
        LazyListState()
      }
  val timePattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
  val timeFormatter =
      remember(timePattern, locale) { DateTimeFormatter.ofPattern(timePattern, locale) }
  val isToday = state.selectedDate == state.today
  val row =
      @Composable { item: PlanningItem ->
        PlanningRow(
            item,
            now,
            zoneId,
            timeFormatter,
            isToday,
            onItemClick.takeIf { canOpenItem(item) }?.let { open -> { open(item) } },
        )
      }
  Column(modifier.fillMaxWidth()) {
    if (state.isLoading) {
      val label = stringResource(R.string.planning_loading)
      LinearProgressIndicator(
          Modifier.fillMaxWidth().testTag(C.Tag.planning_loading).semantics {
            contentDescription = label
          }
      )
    }
    if (state.hasError) {
      Column(Modifier.padding(16.dp).testTag(C.Tag.planning_error)) {
        Text(stringResource(R.string.planning_error))
        LakeButton(
            text = stringResource(R.string.planning_retry),
            onClick = onRetry,
            style = LakeButtonStyle.Text,
            modifier = Modifier.testTag(C.Tag.planning_retry),
        )
      }
    }
    if (state.isNotShared && caregiverOf != null) {
      // Keep calendar navigation available without showing the patient's unshared entries.
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        header?.invoke()
        NotSharedMessage(caregiverOf, CarePermission.PLANNING)
      }
    } else if (
        (state.untimedItems.isEmpty() && state.scheduledItems.isEmpty()) &&
            !state.isLoading &&
            !state.hasError
    ) {
      Column(
          Modifier.fillMaxSize()
              .then(
                  if (header != null) Modifier.verticalScroll(rememberScrollState()) else Modifier
              )
              .padding(bottom = if (header != null) 96.dp else 0.dp),
          verticalArrangement = if (header == null) Arrangement.Center else Arrangement.Top,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        header?.invoke()
        Column(
            Modifier.padding(24.dp).testTag(C.Tag.planning_empty),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(stringResource(R.string.planning_empty), style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.height(8.dp))
          if (canAdd)
              Text(
                  stringResource(R.string.planning_empty_hint),
                  style = MaterialTheme.typography.bodyMedium,
              )
        }
      }
    } else {
      LazyColumn(
          Modifier.testTag(C.Tag.planning_list),
          state = listState,
          contentPadding = PaddingValues(bottom = listEnd),
      ) {
        // Compact windows give the header and agenda one viewport, without nested lazy lists.
        if (header != null) item(key = "calendar-header") { header() }
        // A single scrolling agenda keeps untimed medications before all timed entries.
        if (state.untimedItems.isNotEmpty()) {
          item(key = "section:no-time") {
            AgendaHeading(R.string.planning_no_set_time, C.Tag.planning_no_set_time)
          }
          items(state.untimedItems, key = { it.key }) { item -> RowSpacing { row(item) } }
        }
        if (state.scheduledItems.isNotEmpty()) {
          item(key = "section:scheduled") {
            AgendaHeading(R.string.planning_scheduled, C.Tag.planning_scheduled)
          }
          items(state.scheduledItems, key = { it.key }) { item -> RowSpacing { row(item) } }
        }
      }
    }
  }
}

@Composable
private fun AgendaHeading(@StringRes label: Int, tag: String) {
  Text(
      stringResource(label),
      Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag).semantics { heading() },
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/**
 * The space around a row of the agenda (R3: 16 dp at the sides, 8 dp between rows). A row is at
 * most 512 dp wide (R4: a readable line held sideways), so the Add button sits beside it.
 */
@Composable
private fun RowSpacing(content: @Composable () -> Unit) {
  Box(
      Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs).widthIn(max = ROW_MAX_WIDTH)
  ) {
    content()
  }
}

/** R4: rows stop at 512 dp, on the left, in a wide window. */
private val ROW_MAX_WIDTH = 512.dp
