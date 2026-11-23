// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.label
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.overview.NextUpCard
import com.github.se.oncompanion.ui.overview.NothingPlannedCard
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import com.github.se.oncompanion.ui.overview.Shortcuts
import com.github.se.oncompanion.ui.overview.TodayItemKind
import com.github.se.oncompanion.ui.overview.cardBorder
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardDetail
import com.github.se.oncompanion.ui.theme.cardEyebrow
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * "Alex's day" and what Alex shares of it: Next up while they share their planning, their newest
 * journal entry while they share their symptoms, a locked card for each section they keep private,
 * under a line saying only they can change it.
 */
@Composable
internal fun CaregiverDay(
    uiState: CaregiverOverviewUiState,
    patientName: String,
    actions: CaregiverOverviewActions,
) {
  Column(
      Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.lg, bottom = Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Text(
        stringResource(R.string.caregiver_overview_day, patientName),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(C.Tag.caregiver_overview_day_title),
    )
    when {
      uiState.hasError ->
          LakeMessageState(
              title = stringResource(R.string.caregiver_overview_error_title, patientName),
              message = stringResource(R.string.overview_error_hint_caregiver),
              actionLabel = stringResource(R.string.overview_retry),
              onAction = actions.onRetry,
              actionTestTag = C.Tag.caregiver_overview_retry,
              modifier = Modifier.testTag(C.Tag.caregiver_overview_error),
          )
      uiState.isLoading ->
          Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.testTag(C.Tag.caregiver_overview_loading))
          }
      else -> SharedDay(uiState, patientName, actions)
    }
    // Until the day has a card for each, the shared sections keep their tiles, as before; the
    // journal card already opens the symptoms
    val tiles =
        OverviewShortcut.entries.filter {
          it.section != null &&
              it != OverviewShortcut.SYMPTOMS &&
              uiState.sections[it.section] == SectionVisibility.SHOWN
        }
    if (tiles.isNotEmpty()) Shortcuts(actions.onOpenShortcut, tiles, withQuestion = false)
  }
}

/** The sections of the day in their order: what is shared, or a locked card in its place. */
@Composable
private fun SharedDay(
    uiState: CaregiverOverviewUiState,
    patientName: String,
    actions: CaregiverOverviewActions,
) {
  if (uiState.privateSections.isNotEmpty()) {
    Text(
        stringResource(R.string.caregiver_overview_shared_only, patientName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(C.Tag.caregiver_overview_shared_only),
    )
  }
  uiState.sections.forEach { (section, visibility) ->
    when {
      visibility == SectionVisibility.PRIVATE -> PrivateCard(section, patientName)
      // The other shared sections get their cards in the next steps of the redesign
      section == CarePermission.PLANNING -> PlanningCard(uiState, actions)
      section == CarePermission.SYMPTOMS ->
          JournalCard(uiState.latestEntry, uiState.now.toLocalDate(), actions.onOpenJournal)
    }
  }
}

/** Next up, or "Nothing planned" when the patient has nothing coming. */
@Composable
private fun PlanningCard(uiState: CaregiverOverviewUiState, actions: CaregiverOverviewActions) {
  val nextUp = uiState.nextUp
  if (nextUp == null) {
    NothingPlannedCard(dayStarted = false, actions.onOpenPlanning, Modifier)
    return
  }
  NextUpCard(
      nextUp,
      uiState.now.toLocalDate(),
      onOpenPlanning = actions.onOpenPlanning,
      onOpen = {
        if (nextUp.kind == TodayItemKind.EVENT) actions.onOpenEvent(nextUp.id)
        else actions.onOpenAppointment(nextUp.id)
      },
      modifier = Modifier,
  )
}

/** "Planning · Alex keeps this private", in place of a section the patient doesn't share. */
@Composable
private fun PrivateCard(section: CarePermission, patientName: String) {
  Surface(
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.caregiverOverviewPrivate(section.name)),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.lakeColors.stone.card,
      border = cardBorder(),
  ) {
    Row(
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      // The text says it is private: the lock is decorative
      Icon(
          painterResource(R.drawable.ic_lock),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(LOCK_SIZE),
      )
      Column {
        Text(
            stringResource(section.label),
            style = MaterialTheme.typography.cardEyebrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.caregiver_overview_private, patientName),
            style = MaterialTheme.typography.cardDetail,
            color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

/** The Figma's lock, 20 dp. */
private val LOCK_SIZE = 20.dp
