// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * What the caregiver's Overview needs to open "Whose care?" over itself.
 *
 * @property viewModel the people the caregiver follows
 * @property onChoose shows another person's day
 * @property onFollowSomeoneNew opens the screen to enter an invite code
 * @property onSignedOut called when nobody is signed in anymore, to sign in again
 */
class WhoseCareSwitch(
    val viewModel: WhoseCareViewModel,
    val onChoose: (FollowedPatient) -> Unit,
    val onFollowSomeoneNew: () -> Unit,
    val onSignedOut: () -> Unit,
)

/**
 * "Whose care?" as a sheet over the caregiver's Overview, opened from the boat in its header (Figma
 * M3: "Whose care? sheet", #564): the people the caregiver follows, the one shown now ticked, then
 * "Follow someone new".
 *
 * @param currentUid the patient whose day the Overview shows
 * @param onDismiss closes the sheet
 * @param onChoose shows another person's day
 * @param onFollowSomeoneNew opens the screen to enter an invite code
 * @param onSignedOut called when nobody is signed in anymore: the list can't be read until they
 *   sign in again
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhoseCareSheet(
    viewModel: WhoseCareViewModel,
    currentUid: String,
    onDismiss: () -> Unit,
    onChoose: (FollowedPatient) -> Unit,
    onFollowSomeoneNew: () -> Unit,
    onSignedOut: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // As on the full screen: Retry can't work, so the caregiver goes to sign in
  LaunchedEffect(uiState.isSignedOut) { if (uiState.isSignedOut) onSignedOut() }
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      // Opens whole: half of a phone held sideways shows only the first person
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      containerColor = MaterialTheme.lakeColors.stone.page,
  ) {
    WhoseCareSheetContent(
        uiState = uiState,
        currentUid = currentUid,
        onChoose = onChoose,
        onDismiss = onDismiss,
        onFollowSomeoneNew = onFollowSomeoneNew,
        onRetry = viewModel::load,
    )
  }
}

/**
 * What the sheet shows in [uiState], without a ViewModel, so tests can check every state. The
 * person shown now only closes the sheet. "Follow someone new" stays under every state, so a list
 * that can't load never stops the caregiver from joining another circle.
 */
@Composable
internal fun WhoseCareSheetContent(
    uiState: WhoseCareUiState,
    currentUid: String,
    onChoose: (FollowedPatient) -> Unit,
    onDismiss: () -> Unit,
    onFollowSomeoneNew: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // Scrolls whole, so four people at a large font, or a phone held sideways, reach every row
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl)
              .testTag(C.Tag.whose_care_sheet),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    Text(
        stringResource(R.string.whose_care_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Spacing.xs).semantics { heading() },
    )
    when {
      uiState.isLoading ->
          Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(PROGRESS).testTag(C.Tag.whose_care_loading))
          }
      uiState.hasError ->
          LakeMessageState(
              title = stringResource(R.string.whose_care_error),
              actionLabel = stringResource(R.string.whose_care_retry),
              onAction = onRetry,
              actionTestTag = C.Tag.whose_care_action,
              modifier = Modifier.testTag(C.Tag.whose_care_error),
          )
      else ->
          uiState.patients.forEach { patient ->
            val isCurrent = patient.uid == currentUid
            PersonRow(
                patient,
                isCurrent,
                onClick = { if (isCurrent) onDismiss() else onChoose(patient) },
            )
          }
    }
    FollowSomeoneNew(onFollowSomeoneNew)
  }
}

/** One person: their initial and first name, then a tick if shown now, a chevron otherwise. */
@Composable
private fun PersonRow(patient: FollowedPatient, isCurrent: Boolean, onClick: () -> Unit) {
  val highlight = if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else null
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = ROW_HEIGHT)
              .clip(MaterialTheme.shapes.large)
              .then(if (highlight != null) Modifier.background(highlight) else Modifier)
              // Screen readers say "selected" on the person shown now
              .selectable(selected = isCurrent, onClick = onClick)
              .padding(horizontal = Spacing.sm)
              .testTag(C.Tag.whoseCarePatient(patient.uid)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Monogram(patient.firstName.take(1).uppercase())
    Text(
        patient.firstName,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
    )
    // Decorative: "selected" already says which one is shown
    Icon(
        painterResource(if (isCurrent) R.drawable.ic_check else R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
    )
  }
}

/** "+", "Follow someone new" and "Enter the invite code they shared". */
@Composable
private fun FollowSomeoneNew(onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = ROW_HEIGHT)
              .clip(MaterialTheme.shapes.large)
              .clickable(onClick = onClick)
              .padding(horizontal = Spacing.sm)
              .testTag(C.Tag.whose_care_follow_new),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    Box(
        Modifier.size(MONOGRAM_SIZE)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(R.drawable.ic_add),
          contentDescription = null,
          tint = MaterialTheme.lakeColors.primaryText,
      )
    }
    Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
      Text(
          stringResource(R.string.whose_care_follow_new),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.lakeColors.primaryText,
      )
      Text(
          stringResource(R.string.whose_care_follow_new_hint),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// The Figma's 64 dp rows
private val ROW_HEIGHT = 64.dp
private val PROGRESS = 32.dp
