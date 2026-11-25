// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.common.PaperLakeHeader
import com.github.se.oncompanion.ui.common.PaperLakeStatusBar
import com.github.se.oncompanion.ui.common.formatDateHeading
import com.github.se.oncompanion.ui.common.isPaperLakeHeaderBand
import com.github.se.oncompanion.ui.common.rememberAppDateFormatter
import com.github.se.oncompanion.ui.navigation.BottomBarUiState
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.notification.CircleSheet
import com.github.se.oncompanion.ui.notification.CircleSheetViewModel
import com.github.se.oncompanion.ui.notification.SailboatBadge
import com.github.se.oncompanion.ui.notification.SailboatButton
import com.github.se.oncompanion.ui.notification.sailboatBadge
import com.github.se.oncompanion.ui.symptom.AddSymptomViewModel
import com.github.se.oncompanion.ui.symptom.label
import com.github.se.oncompanion.ui.theme.Shapes
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDateTime

/**
 * A section the patient can open from the Overview with a single tap.
 *
 * @property section the section a caregiver needs shared to get this tile, or `null` if they never
 *   get it
 */
enum class OverviewShortcut(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val testTag: String,
    val section: CarePermission? = null,
) {
  SYMPTOMS(
      Route.SYMPTOMS,
      R.string.symptoms_title,
      R.drawable.ic_symptoms,
      C.Tag.overview_shortcut_symptoms,
      CarePermission.SYMPTOMS,
  ),
  PRESCRIPTIONS(
      Route.PRESCRIPTIONS,
      R.string.prescriptions_title,
      R.drawable.ic_prescriptions,
      C.Tag.overview_shortcut_prescriptions,
      CarePermission.PRESCRIPTIONS,
  ),
  /** Shown as the account icon in the top bar rather than as a tile. */
  PROFILE(
      Route.PROFILE,
      R.string.profile_title,
      R.drawable.ic_profile,
      C.Tag.overview_shortcut_profile,
  ),
}

/**
 * The caregiver's tiles under "How do you feel today?", in display order. The patient has none: the
 * check-in opens the symptoms, Prescriptions is a tab, and the sailboat opens the care circle.
 */
private val tileShortcuts = listOf(OverviewShortcut.SYMPTOMS, OverviewShortcut.PRESCRIPTIONS)

/** "Saturday 3 October", as in the mockup. Day and month names follow the app language. */
private val todayFormatter
  @Composable get() = rememberAppDateFormatter("EEEE d MMMM")

/**
 * The home screen after signing in (US-23 "See my day at a glance"), following the Figma "US-23 ·
 * Overview dashboard (proposal)": a greeting, the next appointment, today's items and the shortcuts
 * to each section.
 *
 * A caregiver sees it in caregiver mode (US-20): "Anne's care ▾" opens "Whose care?" to switch
 * patient, and the shortcuts are only those of the sections the patient shares (Symptoms and
 * Prescriptions).
 *
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 * @param onSwitchPatient opens "Whose care?", in caregiver mode
 * @param bottomBar what the bottom bar shows on every tab: its tabs and the Prescriptions badge
 * @param checkIn gives the ViewModel that logs what the patient feels from the check-in's sheet
 *   (#544), created on the first tile tapped
 * @param circle reads the patient's care circle from the start, for the sailboat's dot, and fills
 *   the "Your circle" sheet it opens (US-50, #457)
 */
@Composable
fun OverviewScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = viewModel { OverviewViewModel() },
    onOpenAppointment: (String) -> Unit = {
      navigationActions.navigateTo(Screen.appointmentDetail(it))
    },
    onOpenEvent: (String) -> Unit = { navigationActions.navigateTo(Screen.eventDetail(it)) },
    caregiverOf: String? = null,
    onSwitchPatient: () -> Unit = {},
    bottomBar: BottomBarUiState = BottomBarUiState(),
    checkIn: () -> AddSymptomViewModel = checkInViewModel(),
    circle: CircleSheetViewModel = viewModel { CircleSheetViewModel() },
) {
  // The "Your circle" sheet stays open across a rotation; what it shows stays in [circle]
  var isCircleOpen by rememberSaveable { mutableStateOf(false) }
  val circleState by circle.uiState.collectAsStateWithLifecycle()
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Stays open across a rotation; what was picked stays in the check-in's ViewModel
  var isCheckInOpen by rememberSaveable { mutableStateOf(false) }
  // The ViewModel outlives navigation and backgrounding: keep the date, greeting, Next up and
  // statuses current when the Overview comes back, and while it is on screen each time an entry
  // starts or ends and at midnight
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  OverviewContent(
      uiState = uiState,
      onShortcutClick = { shortcut -> navigationActions.navigateTo(shortcut.route) },
      // Planning is a tab; the Overview's links open it on today, not on the last day seen there
      onOpenPlanning = { navigationActions.navigateToPlanningOn(uiState.now.toLocalDate()) },
      onOpenAppointment = onOpenAppointment,
      onOpenEvent = onOpenEvent,
      onLogSymptom = { type ->
        checkIn().startCheckIn(type)
        isCheckInOpen = true
      },
      onSpeak = { navigationActions.navigateTo(Screen.symptomSpeak()) },
      onTickDose = viewModel::tickDose,
      onOpenDoses = viewModel::openDoses,
      onRetry = viewModel::loadDay,
      modifier = modifier,
      caregiverOf = caregiverOf,
      onSwitchPatient = onSwitchPatient,
      bottomBar = {
        BottomNavigationBar(
            selectedTab = Tab.OVERVIEW,
            onTabSelected = { tab -> navigationActions.navigateToTab(tab.route) },
            state = bottomBar,
        )
      },
      sailboatBadge = circleState.sailboatBadge,
      onOpenCircle = { isCircleOpen = true },
  )
  uiState.dosesSheet?.let { sheet ->
    OverviewDosesSheet(
        sheet,
        onLess = viewModel::lessDoses,
        onMore = viewModel::moreDoses,
        onDone = viewModel::saveDoses,
        onClose = viewModel::closeDoses,
    )
  }
  if (isCheckInOpen) {
    CheckInSheetHost(
        checkIn(),
        onSaved = { isCheckInOpen = false },
        onClose = { isCheckInOpen = false },
    )
  }
  if (isCircleOpen) {
    // Each link closes the sheet first, so Back never comes back to it open
    val openFromCircle = { route: String ->
      isCircleOpen = false
      navigationActions.navigateTo(route)
    }
    CircleSheet(
        circle,
        onDismiss = { isCircleOpen = false },
        onManage = { openFromCircle(Screen.CARE_CIRCLE) },
        // Over the Care circle page, so Back from the invite code shows them waiting to join
        onAddSomeone = {
          openFromCircle(Screen.CARE_CIRCLE)
          navigationActions.navigateTo(Screen.CARE_CIRCLE_ADD)
        },
        onOpen = openFromCircle,
    )
  }
}

/**
 * The check-in's ViewModel, kept by the Overview so what was picked survives a rotation. It is only
 * created when first asked for (a tile tapped): an Overview whose sheet never opens doesn't need
 * the phone's speech recognizer.
 */
@Composable
private fun checkInViewModel(): () -> AddSymptomViewModel {
  val owner = checkNotNull(LocalViewModelStoreOwner.current)
  // The app's context, not the activity's: the ViewModel outlives a rotation
  val app = LocalContext.current.applicationContext
  return remember(owner, app) {
    val factory = viewModelFactory {
      initializer { AddSymptomViewModel(symptomName = { app.getString(it.label) }) }
    }
    val get: () -> AddSymptomViewModel = {
      ViewModelProvider(owner, factory)[CHECK_IN_KEY, AddSymptomViewModel::class.java]
    }
    get
  }
}

/** The key of the check-in's ViewModel, beside the Overview's own. */
private const val CHECK_IN_KEY = "check_in"

/**
 * Stateless content of the Overview, so each state can be tested without a ViewModel.
 *
 * The greeting sits on the Paper lake header, in the light of [OverviewUiState.now], and the
 * appointment card overlaps the header's lower edge (DESIGN.md, main screens). Everything scrolls
 * together, so a large font or a phone held sideways never hides the day under a fixed header.
 *
 * @param onOpenPlanning opens Planning from an empty appointment card or "See planning"
 * @param onOpenAppointment opens the recorded appointment directly
 * @param onOpenEvent opens the event shown in the patient's "Next up"
 * @param onTickDose ticks a dose of one of the patient's medications, by its ID
 * @param onOpenDoses opens the sheet to correct the doses of one, by its ID
 * @param onLogSymptom opens the check-in's sheet from the patient's check-in, with that symptom
 *   picked or empty for `null`
 * @param onSpeak opens the symptom form already listening, from the check-in's "Speak how you feel"
 *   (US-73), shown only when [OverviewUiState.canSpeak]
 * @param onRetry reloads the day after an error
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 * @param onSwitchPatient opens "Whose care?", in caregiver mode
 * @param bottomBar the bottom navigation bar, empty by default
 * @param sailboatBadge what the patient's sailboat points out; `null` shows no sailboat
 * @param onOpenCircle opens the "Your circle" sheet from the sailboat
 */
@Composable
fun OverviewContent(
    uiState: OverviewUiState,
    onShortcutClick: (OverviewShortcut) -> Unit,
    onOpenPlanning: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEvent: (String) -> Unit = {},
    onLogSymptom: (SymptomType?) -> Unit = {},
    onSpeak: () -> Unit = {},
    onTickDose: (String) -> Unit = {},
    onOpenDoses: (String) -> Unit = {},
    caregiverOf: String? = null,
    onSwitchPatient: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    sailboatBadge: SailboatBadge? = null,
    onOpenCircle: () -> Unit = {},
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.overview_screen),
      bottomBar = bottomBar,
      // The patient's day sits on the stone page; the caregiver's comes later
      containerColor =
          if (caregiverOf == null) MaterialTheme.lakeColors.stone.page
          else MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    val layoutDirection = LocalLayoutDirection.current
    // The header draws behind the status bar, so the top inset is kept inside it
    Box(
        Modifier.fillMaxSize()
            .padding(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding(),
            )
    ) {
      Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val band = isPaperLakeHeaderBand()
        PaperLakeHeader(uiState.now.toLocalTime(), Modifier.testTag(C.Tag.overview_header)) {
          OverviewHeader(
              uiState,
              caregiverOf,
              onSwitchPatient,
              onProfileClick = { onShortcutClick(OverviewShortcut.PROFILE) },
              band = band,
              // The patient's only: only they manage their care circle
              sailboat =
                  sailboatBadge
                      ?.takeIf { caregiverOf == null }
                      ?.let { badge -> { SailboatButton(badge, onOpenCircle) } },
          )
        }
        val dayShown = !uiState.isLoading && !uiState.hasError && !uiState.isNotShared
        // Only the full header leaves room for a card on its lower edge
        val overlaps = dayShown && !band
        Column(
            modifier =
                Modifier.padding(horizontal = Spacing.lg)
                    .padding(top = if (overlaps) 0.dp else Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
          when {
            uiState.isLoading -> LoadingDay()
            uiState.hasError -> DayError(onRetry, withShortcuts = caregiverOf == null)
            // The patient a caregiver follows doesn't share their planning, so there is no day to
            // show
            uiState.isNotShared && caregiverOf != null ->
                NotSharedMessage(caregiverOf, CarePermission.PLANNING)
            // The patient's day follows the Overview UX Refactor; the caregiver's comes later
            caregiverOf == null ->
                PatientDay(
                    uiState,
                    onOpenPlanning,
                    onOpenAppointment,
                    onOpenEvent,
                    modifier = if (overlaps) Modifier.overlapLayout() else Modifier,
                    onTickDose = onTickDose,
                    onOpenDoses = onOpenDoses,
                )
            else -> {
              NextAppointmentCard(
                  uiState.nextAppointment,
                  uiState.now.toLocalDate(),
                  onOpenPlanning,
                  onOpenAppointment,
                  modifier = if (overlaps) Modifier.overlapHeader() else Modifier,
              )
              TodaySection(uiState.todayEntries, onOpenPlanning)
            }
          }
          // The patient's check-in, and the caregiver's shortcuts to the sections the patient
          // shares, stay available whatever happens to the day's data
          if (caregiverOf == null) {
            CheckInDock(
                onLogSymptom = onLogSymptom,
                onOpenJournal = { onShortcutClick(OverviewShortcut.SYMPTOMS) },
                onSpeak = onSpeak.takeIf { uiState.canSpeak },
            )
          } else {
            val tiles = tileShortcuts.filter { it.section in uiState.sharedSections }
            if (tiles.isNotEmpty()) Shortcuts(onShortcutClick, tiles)
          }
        }
      }
      // Drawn last, so scrolled content passes under it rather than under the clock and icons
      PaperLakeStatusBar(uiState.now.toLocalTime(), Modifier.testTag(C.Tag.overview_status_bar))
    }
  }
}

/** How far the first card reaches up over the header, as in the Figma (header 210, card at 168). */
private val HEADER_OVERLAP = 42.dp

/**
 * Moves the card up over the header's lower edge without leaving a gap under it, and raises it with
 * Elevation 1, the shadow DESIGN.md keeps for this card.
 */
private fun Modifier.overlapHeader(): Modifier = overlapLayout().elevation1(Shapes.large)

/** Moves the card up over the header's lower edge without leaving a gap under it. */
private fun Modifier.overlapLayout(): Modifier = layout { measurable, constraints ->
  val placeable = measurable.measure(constraints)
  val overlap = HEADER_OVERLAP.roundToPx()
  layout(placeable.width, placeable.height - overlap) { placeable.place(0, -overlap) }
}

/**
 * The greeting (or "Anne's care ▾" for a caregiver), today's date, the patient's sailboat and the
 * profile button, on the sky of the header. On the full header, room is left under them for the
 * overlapping card.
 *
 * @param band whether the header is the small band, where the title is one size smaller
 * @param sailboat the patient's "Your circle" button, just before the profile; none for a caregiver
 */
@Composable
private fun OverviewHeader(
    uiState: OverviewUiState,
    caregiverOf: String?,
    onSwitchPatient: () -> Unit,
    onProfileClick: () -> Unit,
    band: Boolean,
    sailboat: (@Composable () -> Unit)? = null,
) {
  val titleStyle =
      if (band) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.lakeColors.onHorizon) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = Spacing.lgPlus,
                    top = Spacing.lg,
                    end = Spacing.sm,
                    bottom = if (band) Spacing.lg else HEADER_OVERLAP + Spacing.lg,
                ),
    ) {
      Box(Modifier.weight(1f)) {
        if (caregiverOf == null) Greeting(uiState.firstName, uiState.now, titleStyle)
        else CareTitle(caregiverOf, uiState.now, onSwitchPatient, titleStyle)
      }
      // The sailboat opening "Your circle", just before the profile (Figma M3)
      sailboat?.invoke()
      val profile = OverviewShortcut.PROFILE
      IconButton(onClick = onProfileClick, modifier = Modifier.testTag(profile.testTag)) {
        Icon(
            painter = painterResource(profile.icon),
            contentDescription = stringResource(profile.label),
        )
      }
    }
  }
}

/** "Anne's care ▾" and today's date, for a caregiver: tapping it opens "Whose care?". */
@Composable
private fun CareTitle(
    patientName: String,
    now: LocalDateTime,
    onClick: () -> Unit,
    titleStyle: TextStyle,
) {
  Column(
      Modifier.clickable(
              onClickLabel = stringResource(R.string.overview_switch_patient),
              onClick = onClick,
          )
          .testTag(C.Tag.overview_whose_care),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.overview_care_of, patientName), style = titleStyle)
      // The click label already says what it does
      Text(" ▾", style = titleStyle, modifier = Modifier.clearAndSetSemantics {})
    }
    TodayDate(now)
  }
}

/** "Good morning, Alex" and today's date, on the header. */
@Composable
internal fun Greeting(firstName: String?, now: LocalDateTime, titleStyle: TextStyle) {
  val name = firstName?.takeIf { it.isNotBlank() }
  val greeting =
      when (now.hour) {
        in 0..11 ->
            if (name == null) stringResource(R.string.overview_greeting_morning)
            else stringResource(R.string.overview_greeting_morning_name, name)
        in 12..17 ->
            if (name == null) stringResource(R.string.overview_greeting_afternoon)
            else stringResource(R.string.overview_greeting_afternoon_name, name)
        else ->
            if (name == null) stringResource(R.string.overview_greeting_evening)
            else stringResource(R.string.overview_greeting_evening_name, name)
      }
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(text = greeting, style = titleStyle, modifier = Modifier.testTag(C.Tag.overview_greeting))
    TodayDate(now)
  }
}

/** "Saturday 3 October", under the title. */
@Composable
private fun TodayDate(now: LocalDateTime) {
  Text(
      text = todayFormatter.formatDateHeading(now),
      style = MaterialTheme.typography.bodyLarge,
      modifier = Modifier.testTag(C.Tag.overview_date),
  )
}

@Composable
private fun LoadingDay() {
  Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.testTag(C.Tag.overview_loading))
  }
}

/**
 * Shown when the appointment and today's items couldn't be loaded. A caregiver has no shortcuts to
 * fall back on, so the hint doesn't mention them ([withShortcuts] false).
 */
@Composable
private fun DayError(onRetry: () -> Unit, withShortcuts: Boolean) {
  LakeMessageState(
      title = stringResource(R.string.overview_error_title),
      message =
          stringResource(
              if (withShortcuts) R.string.overview_error_hint
              else R.string.overview_error_hint_caregiver
          ),
      actionLabel = stringResource(R.string.overview_retry),
      onAction = onRetry,
      actionTestTag = C.Tag.overview_retry,
      modifier = Modifier.testTag(C.Tag.overview_error),
  )
}

/** Space between two tiles. */
private val tileSpacing = 12.dp

/** Horizontal padding inside a tile. */
private val tilePadding = 8.dp

/**
 * "How do you feel today?" ([withQuestion]) and one large tile per main section: three in a row, or
 * one per row when a label wouldn't fit in a third of the width (small screen or large font).
 */
@Composable
internal fun Shortcuts(
    onShortcutClick: (OverviewShortcut) -> Unit,
    tiles: List<OverviewShortcut>,
    withQuestion: Boolean = true,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (withQuestion) {
      Text(
          text = stringResource(R.string.overview_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(C.Tag.overview_shortcuts)) {
      val labelStyle = MaterialTheme.typography.titleMedium
      val labels = tiles.map { stringResource(it.label) }
      val measurer = rememberTextMeasurer()
      val density = LocalDensity.current
      // Measured again only when the labels, their style or the font size change
      val widestLabel =
          remember(labels, labelStyle, density) {
            with(density) { labels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp() }
          }
      val labelSpaceInRow = (maxWidth - tileSpacing * 2) / 3 - tilePadding * 2
      // Large tiles: easy to read and to tap on a low-energy day
      if (widestLabel <= labelSpaceInRow) {
        Row(horizontalArrangement = Arrangement.spacedBy(tileSpacing)) {
          tiles.forEach { shortcut ->
            ShortcutTile(
                shortcut,
                { onShortcutClick(shortcut) },
                Modifier.weight(1f),
            )
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(tileSpacing)) {
          tiles.forEach { shortcut ->
            ShortcutTile(
                shortcut,
                { onShortcutClick(shortcut) },
                Modifier.fillMaxWidth(),
                stacked = true,
            )
          }
        }
      }
    }
  }
}

/** A shortcut tile: icon above the label, or next to it when [stacked] (one tile per row). */
@Composable
private fun ShortcutTile(
    shortcut: OverviewShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
  Surface(
      onClick = onClick,
      modifier =
          modifier
              .fillMaxWidth()
              .heightIn(min = if (stacked) 72.dp else 104.dp)
              .testTag(shortcut.testTag),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.primaryContainer,
  ) {
    // The label already describes the tile, so the icon is decorative
    val icon =
        @Composable {
          Icon(
              painter = painterResource(shortcut.icon),
              contentDescription = null,
              modifier = Modifier.size(32.dp),
          )
        }
    val label =
        @Composable {
          Text(
              text = stringResource(shortcut.label),
              style = MaterialTheme.typography.titleMedium,
              textAlign = if (stacked) TextAlign.Start else TextAlign.Center,
          )
        }
    if (stacked) {
      Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        icon()
        label()
      }
    } else {
      Column(
          modifier = Modifier.padding(horizontal = tilePadding, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
      ) {
        icon()
        label()
      }
    }
  }
}
