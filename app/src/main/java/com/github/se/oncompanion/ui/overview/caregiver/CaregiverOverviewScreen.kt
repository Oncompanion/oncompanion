// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.ChosenPatient
import com.github.se.oncompanion.ui.carecircle.WhoseCareSheet
import com.github.se.oncompanion.ui.carecircle.WhoseCareSwitch
import com.github.se.oncompanion.ui.common.PaperLakeHeader
import com.github.se.oncompanion.ui.common.PaperLakeStatusBar
import com.github.se.oncompanion.ui.common.isPaperLakeHeaderBand
import com.github.se.oncompanion.ui.navigation.BottomBarUiState
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.overview.Greeting
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The caregiver's own Overview (design: M3, "Overview · Caregiver · Alex's day"): "Good morning,
 * Marc" on the Paper lake header, then "Alex's day", with only what Alex shares.
 *
 * @param patient the patient the caregiver follows
 * @param onOpenAppointment opens the appointment in Next up
 * @param onOpenEvent opens the event in Next up
 * @param whoseCare what the three-people button in the header opens: "Whose care?", as a sheet; its
 *   ViewModel also says whether someone logged something new, for the button's dot
 * @param bottomBar what the bottom bar shows: a caregiver's has no Prescriptions tab
 */
@Composable
fun CaregiverOverviewScreen(
    viewModel: CaregiverOverviewViewModel,
    patient: ChosenPatient,
    navigationActions: NavigationActions,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    whoseCare: WhoseCareSwitch,
    modifier: Modifier = Modifier,
    bottomBar: BottomBarUiState = BottomBarUiState(caregiverMode = true),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val whoseCareState by whoseCare.viewModel.uiState.collectAsStateWithLifecycle()
  // Kept open across a rotation, as the patient's sheets
  var isWhoseCareOpen by rememberSaveable { mutableStateOf(false) }
  // Keeps the date, the greeting and Next up current while the Overview is shown
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  // Only while it is on screen: what the person logs with the phone locked isn't seen yet
  LifecycleResumeEffect(whoseCare.viewModel, patient.uid) {
    whoseCare.viewModel.show(patient.uid)
    onPauseOrDispose { whoseCare.viewModel.show(null) }
  }
  CaregiverOverviewContent(
      uiState = uiState,
      patientName = patient.firstName,
      newsKey = whoseCareState.newsKey,
      actions =
          CaregiverOverviewActions(
              onOpenPlanning = {
                navigationActions.navigateToPlanningOn(uiState.now.toLocalDate())
              },
              onOpenAppointment = onOpenAppointment,
              onOpenEvent = onOpenEvent,
              onOpenJournal = { navigationActions.navigateTo(OverviewShortcut.SYMPTOMS.route) },
              onRetry = viewModel::retry,
              onSwitchPatient = { isWhoseCareOpen = true },
              onOpenProfile = { navigationActions.navigateTo(OverviewShortcut.PROFILE.route) },
              onOpenShortcut = { navigationActions.navigateTo(it.route) },
          ),
      modifier = modifier,
      bottomBar = {
        BottomNavigationBar(
            selectedTab = Tab.OVERVIEW,
            onTabSelected = { tab -> navigationActions.navigateToTab(tab.route) },
            state = bottomBar,
        )
      },
  )
  if (isWhoseCareOpen) {
    // Closed first, so Back from the invite code never comes back to it open
    WhoseCareSheet(
        viewModel = whoseCare.viewModel,
        currentUid = patient.uid,
        onDismiss = { isWhoseCareOpen = false },
        onChoose = {
          isWhoseCareOpen = false
          whoseCare.onChoose(it)
        },
        onFollowSomeoneNew = {
          isWhoseCareOpen = false
          whoseCare.onFollowSomeoneNew()
        },
        onSignedOut = whoseCare.onSignedOut,
    )
  }
}

/**
 * What the caregiver can do from their Overview.
 *
 * @property onOpenPlanning opens the patient's Planning
 * @property onOpenAppointment opens the appointment with this ID
 * @property onOpenEvent opens the event with this ID
 * @property onOpenJournal opens the patient's symptom journal
 * @property onRetry loads the Overview again after an error
 * @property onSwitchPatient opens "Whose care?" to follow another patient
 * @property onOpenProfile opens the caregiver's profile
 * @property onOpenShortcut opens the section of a shortcut tile
 */
data class CaregiverOverviewActions(
    val onOpenPlanning: () -> Unit = {},
    val onOpenAppointment: (String) -> Unit = {},
    val onOpenEvent: (String) -> Unit = {},
    val onOpenJournal: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSwitchPatient: () -> Unit = {},
    val onOpenProfile: () -> Unit = {},
    val onOpenShortcut: (OverviewShortcut) -> Unit = {},
)

/**
 * Stateless content of the caregiver's Overview, so each state can be tested without a ViewModel.
 * Everything scrolls together, so a large font or a phone held sideways never hides the day.
 *
 * @param newsKey which entries of the people followed are new, for the header button's dot and
 *   glow; `null` when none is
 */
@Composable
fun CaregiverOverviewContent(
    uiState: CaregiverOverviewUiState,
    patientName: String,
    actions: CaregiverOverviewActions,
    modifier: Modifier = Modifier,
    newsKey: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.caregiver_overview_screen),
      bottomBar = bottomBar,
      containerColor = MaterialTheme.lakeColors.stone.page,
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
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaperLakeHeader(uiState.now.toLocalTime(), Modifier.testTag(C.Tag.overview_header)) {
          CaregiverHeader(uiState, newsKey, actions)
        }
        CaregiverDay(uiState, patientName, actions)
      }
      // Drawn last, so scrolled content passes under it rather than under the clock and icons
      PaperLakeStatusBar(uiState.now.toLocalTime(), Modifier.testTag(C.Tag.overview_status_bar))
    }
  }
}

/** "Good morning, Marc", today's date, "Whose care?" and the profile, on the sky of the header. */
@Composable
private fun CaregiverHeader(
    uiState: CaregiverOverviewUiState,
    newsKey: String?,
    actions: CaregiverOverviewActions,
) {
  val titleStyle =
      if (isPaperLakeHeaderBand()) MaterialTheme.typography.headlineMedium
      else MaterialTheme.typography.headlineLarge
  CompositionLocalProvider(LocalContentColor provides MaterialTheme.lakeColors.onHorizon) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = Spacing.lgPlus,
                top = Spacing.lg,
                end = Spacing.sm,
                bottom = Spacing.lg,
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
      Box(Modifier.weight(1f)) { Greeting(uiState.firstName, uiState.now, titleStyle) }
      WhoseCareButton(newsKey, onClick = actions.onSwitchPatient)
      val profile = OverviewShortcut.PROFILE
      IconButton(onClick = actions.onOpenProfile, modifier = Modifier.testTag(profile.testTag)) {
        Icon(painterResource(profile.icon), contentDescription = stringResource(profile.label))
      }
    }
  }
}
