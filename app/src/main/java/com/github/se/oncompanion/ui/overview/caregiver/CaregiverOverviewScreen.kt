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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.PaperLakeHeader
import com.github.se.oncompanion.ui.common.PaperLakeStatusBar
import com.github.se.oncompanion.ui.common.isPaperLakeHeaderBand
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.BottomBarUiState
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.overview.Greeting
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The caregiver's own Overview (Figma M3, "Home · Caregiver"): "Good morning, Marc" on the Paper
 * lake header, then "Alex's day", with only what Alex shares.
 *
 * @param patientName the first name of the patient the caregiver follows
 * @param onOpenAppointment opens the appointment in Next up
 * @param onOpenEvent opens the event in Next up
 * @param onSwitchPatient opens "Whose care?"
 * @param bottomBar what the bottom bar shows: a caregiver's has no Prescriptions tab
 */
@Composable
fun CaregiverOverviewScreen(
    viewModel: CaregiverOverviewViewModel,
    patientName: String,
    navigationActions: NavigationActions,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onSwitchPatient: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: BottomBarUiState = BottomBarUiState(caregiverMode = true),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Keeps the date, the greeting and Next up current while the Overview is shown
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  CaregiverOverviewContent(
      uiState = uiState,
      patientName = patientName,
      actions =
          CaregiverOverviewActions(
              onOpenPlanning = {
                navigationActions.navigateToPlanningOn(uiState.now.toLocalDate())
              },
              onOpenAppointment = onOpenAppointment,
              onOpenEvent = onOpenEvent,
              onOpenJournal = { navigationActions.navigateTo(OverviewShortcut.SYMPTOMS.route) },
              onRetry = viewModel::retry,
              onSwitchPatient = onSwitchPatient,
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
}

/** What the caregiver can do from their Overview. */
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
 */
@Composable
fun CaregiverOverviewContent(
    uiState: CaregiverOverviewUiState,
    patientName: String,
    actions: CaregiverOverviewActions,
    modifier: Modifier = Modifier,
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
          CaregiverHeader(uiState, actions)
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
private fun CaregiverHeader(uiState: CaregiverOverviewUiState, actions: CaregiverOverviewActions) {
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
      IconButton(
          onClick = actions.onSwitchPatient,
          // The same tag as the old caregiver title that opened "Whose care?"
          modifier = Modifier.testTag(C.Tag.overview_whose_care),
      ) {
        Icon(
            painterResource(R.drawable.ic_care_circle),
            contentDescription = stringResource(R.string.overview_switch_patient),
        )
      }
      val profile = OverviewShortcut.PROFILE
      IconButton(onClick = actions.onOpenProfile, modifier = Modifier.testTag(profile.testTag)) {
        Icon(painterResource(profile.icon), contentDescription = stringResource(profile.label))
      }
    }
  }
}
