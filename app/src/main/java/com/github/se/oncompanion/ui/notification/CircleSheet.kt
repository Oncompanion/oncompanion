// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.CardLink
import com.github.se.oncompanion.ui.common.LINK_HEIGHT
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.rememberDayFormatter
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The "Your circle" sheet over the Overview, opened from the sailboat in its header (US-50, #457).
 *
 * @param onManage opens the Care circle page
 * @param onAddSomeone invites someone, when nobody is in the circle yet (US-13)
 * @param onOpen opens a card's route, picked by [CircleNotificationCard]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CircleSheet(
    viewModel: CircleSheetViewModel,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onAddSomeone: () -> Unit,
    onOpen: (String) -> Unit,
) {
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      containerColor = MaterialTheme.lakeColors.stone.page,
  ) {
    CircleSheetScreen(viewModel, onManage, onAddSomeone, onOpen)
  }
}

/** The sheet's content, read from [viewModel]; [CircleSheet] puts it in the sheet. */
@Composable
internal fun CircleSheetScreen(
    viewModel: CircleSheetViewModel,
    onManage: () -> Unit,
    onAddSomeone: () -> Unit,
    onOpen: (String) -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Keeps "Today" and "Yesterday" right while the sheet is on screen: refreshes when it comes back,
  // then at each midnight, as on the journal
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  CircleSheetContent(uiState, onManage, onAddSomeone, onOpen, viewModel::retry)
}

/**
 * What the sheet shows in [uiState], without a ViewModel, so tests can check every state: one under
 * the other in a column that scrolls whole.
 */
@Composable
internal fun CircleSheetContent(
    uiState: CircleSheetUiState,
    onManage: () -> Unit,
    onAddSomeone: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // Scrolls whole, so a long week or a large font still reaches every action, also sideways
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl)
              .testTag(C.Tag.circle_sheet),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    when (uiState) {
      // The title stays while loading or after an error (DESIGN.md: the screen keeps its header)
      CircleSheetUiState.Loading -> {
        Title()
        Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.size(PROGRESS).testTag(C.Tag.circle_sheet_loading))
        }
      }
      CircleSheetUiState.Error -> {
        Title()
        LakeMessageState(
            title = stringResource(R.string.care_circle_error),
            actionLabel = stringResource(R.string.care_circle_retry),
            onAction = onRetry,
            actionTestTag = C.Tag.circle_sheet_retry,
            modifier = Modifier.testTag(C.Tag.circle_sheet_error),
        )
      }
      CircleSheetUiState.NobodyYet -> NobodyYet(onAddSomeone)
      is CircleSheetUiState.Circle -> Circle(uiState, onManage, onOpen)
    }
  }
}

/** The circle's members, then its notifications by day (Figma: "Note and prescription"). */
@Composable
private fun Circle(
    circle: CircleSheetUiState.Circle,
    onManage: () -> Unit,
    onOpen: (String) -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val manageDescription = stringResource(R.string.circle_sheet_manage_description)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Title(Modifier.weight(1f))
    CardLink(
        stringResource(R.string.circle_sheet_manage),
        onManage,
        // "Manage your circle" for TalkBack, as "Manage" alone could be anything
        Modifier.heightIn(min = LINK_HEIGHT)
            .semantics { contentDescription = manageDescription }
            .testTag(C.Tag.circle_sheet_manage),
    )
  }
  // "Marc, Sophie · Julie invited": who can follow the patient's care
  val invited =
      circle.invited
          .takeIf { it.isNotEmpty() }
          ?.let { stringResource(R.string.circle_sheet_invited, it.joinToString(", ")) }
  val names = listOfNotNull(circle.joined.takeIf { it.isNotEmpty() }?.joinToString(", "), invited)
  if (names.isNotEmpty()) {
    Text(
        text =
            names.singleOrNull()
                ?: stringResource(R.string.circle_sheet_members_and_invited, names[0], names[1]),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.testTag(C.Tag.circle_sheet_members),
    )
  }
  if (circle.days.none { it.date == circle.today }) {
    Text(
        text = stringResource(R.string.circle_sheet_nothing_today),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.testTag(C.Tag.circle_sheet_nothing_today),
    )
  }
  val olderDay = rememberDayFormatter("EEEEdMMMM")
  circle.days.forEach { day ->
    Text(
        text =
            when (day.date) {
              circle.today -> stringResource(R.string.circle_sheet_today)
              circle.today.minusDays(1) -> stringResource(R.string.circle_sheet_yesterday)
              else -> day.date.format(olderDay)
            },
        // A group header in a list (DESIGN.md)
        style = MaterialTheme.typography.titleSmall,
        color = colors.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    day.cards.forEach { CircleNotificationCard(it, onOpen) }
  }
}

/** "No one in your circle yet", and the way to invite someone (Figma: "Nobody yet"). */
@Composable
private fun NobodyYet(onAddSomeone: () -> Unit) {
  Title()
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.lakeColors.stone.card,
      contentColor = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.circle_sheet_nobody_yet),
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      EmptyCircles()
      Text(
          text = stringResource(R.string.care_circle_empty_title),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          text = stringResource(R.string.circle_sheet_nobody_message),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  LakeButton(
      text = stringResource(R.string.circle_sheet_add_someone),
      onClick = onAddSomeone,
      icon = painterResource(R.drawable.ic_add),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.circle_sheet_add_someone),
  )
}

@Composable
private fun Title(modifier: Modifier = Modifier) {
  Text(
      text = stringResource(R.string.circle_sheet_title),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = modifier.semantics { heading() },
  )
}

/** Three empty dashed seats: the people the patient hasn't added yet. Decorative. */
@Composable
private fun EmptyCircles() {
  val color = MaterialTheme.colorScheme.outline
  // Each seat starts a little past the middle of the one before, as in the Figma
  Canvas(
      Modifier.size(width = SEAT * (1 + SEAT_STEP * (SEATS - 1)), height = SEAT)
          .testTag(C.Tag.circle_sheet_seats)
  ) {
    val radius = size.height / 2
    val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))
    repeat(SEATS) { i ->
      drawCircle(
          color = color,
          radius = radius - 1.dp.toPx(),
          center = Offset(radius + i * size.height * SEAT_STEP, radius),
          style = Stroke(width = 1.dp.toPx(), pathEffect = dashes),
      )
    }
  }
}

// The Figma's sizes
private val PROGRESS = 48.dp
private val SEAT = 36.dp
private const val SEATS = 3
// How far each seat is from the one before, as a share of its width
private const val SEAT_STEP = 0.55f
private val DASH = 3.dp
