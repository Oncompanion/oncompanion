// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.LinkAction
import com.github.se.oncompanion.ui.symptom.symptomTimeFormatter
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The patient's check-in: its tiles, or, once they saved an entry today, what they saved until the
 * day ends (Figma M3: After saving).
 */
@Composable
internal fun PatientCheckIn(
    uiState: OverviewUiState,
    onLogSymptom: (SymptomType?) -> Unit,
    onSpeak: () -> Unit,
    onUndoSaved: (String) -> Unit,
    onEditSaved: (String) -> Unit,
    onOpenJournal: () -> Unit,
) {
  val saved = uiState.savedToday
  // Voice starts only from the Overview, so it stays once something was saved
  val speak = onSpeak.takeIf { uiState.canSpeak }
  if (saved == null) {
    CheckInDock(onLogSymptom = onLogSymptom, onOpenJournal = onOpenJournal, onSpeak = speak)
  } else {
    SavedCheckIn(
        saved,
        onUndo = { onUndoSaved(saved.entry.id) },
        onEdit = { onEditSaved(saved.entry.id) },
        onLogAnother = { onLogSymptom(null) },
        onOpenJournal = onOpenJournal,
        onSpeak = speak,
    )
  }
}

/**
 * The check-in once the patient saved how they feel today (Figma M3: Check-in · After saving):
 * "Saved to your journal" and the entry, e.g. "09:30 Nausea 7/10", which they can undo or edit,
 * then "Log another symptom or note" and "Speak how you feel". It stays until the day ends, the
 * entry showing the last one saved.
 *
 * @param saved the entry saved last today
 * @param onUndo deletes it
 * @param onEdit opens it in the symptom form
 * @param onLogAnother opens the check-in's sheet, empty
 * @param onOpenJournal opens the journal, from "Journal ›"
 * @param onSpeak opens the symptom form already listening; `null` when the phone can't listen, so
 *   there is no button
 */
@Composable
internal fun SavedCheckIn(
    saved: LatestEntry,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onLogAnother: () -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
    onSpeak: (() -> Unit)? = null,
) {
  val shape = MaterialTheme.shapes.extraLarge
  // With a large font, the rows stack instead of squeezing the words between the buttons
  val isLargeFont = LocalDensity.current.fontScale >= LARGE_FONT
  Surface(
      modifier = modifier.fillMaxWidth().elevation1(shape).testTag(C.Tag.overview_saved),
      shape = shape,
      color = MaterialTheme.lakeColors.stone.dock,
      border = cardBorder(),
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
      if (isLargeFont) {
        SavedTitle()
        JournalLink(onOpenJournal)
      } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
          CardBadge(R.drawable.ic_check)
          SavedTitle(Modifier.weight(1f))
          JournalLink(onOpenJournal)
        }
      }
      SavedEntry(saved, onUndo, onEdit, isLargeFont)
      LakeButton(
          text = stringResource(R.string.overview_saved_log_another),
          onClick = onLogAnother,
          style = LakeButtonStyle.Tonal,
          icon = painterResource(R.drawable.ic_add),
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_saved_log_another),
      )
      // Under "Log another", as under the tiles (Figma: Speak instead · After saving)
      if (onSpeak != null) SpeakButton(onSpeak)
    }
  }
}

/** "Saved to your journal", then "Your notes today". */
@Composable
private fun SavedTitle(modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(
        stringResource(R.string.overview_saved_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(R.string.overview_saved_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** The entry's time and what was felt, then Undo and Edit: in one row, or two with a large font. */
@Composable
private fun SavedEntry(
    saved: LatestEntry,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    isLargeFont: Boolean,
) {
  val padding = Modifier.padding(start = Spacing.md, end = Spacing.xs)
  Surface(
      shape = RoundedCornerShape(ENTRY_CORNER),
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_saved_entry),
  ) {
    if (isLargeFont) {
      Column(padding.padding(top = Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { EntryText(saved) }
        Row(Modifier.align(Alignment.End)) { EntryActions(onUndo, onEdit) }
      }
    } else {
      Row(
          padding.heightIn(min = 48.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        EntryText(saved)
        EntryActions(onUndo, onEdit)
      }
    }
  }
}

/** "09:30", then "Nausea 7/10 · Fatigue 6/10", taking the room left. */
@Composable
private fun RowScope.EntryText(saved: LatestEntry) {
  Text(
      saved.at.format(symptomTimeFormatter),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
      entrySummary(saved),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
  )
}

/** Undo and Edit as the Figma's short text actions, so the summary keeps most of the row. */
@Composable
private fun EntryActions(onUndo: () -> Unit, onEdit: () -> Unit) {
  val color = MaterialTheme.lakeColors.primaryText
  LinkAction(
      stringResource(R.string.overview_saved_undo),
      color,
      onUndo,
      Modifier.testTag(C.Tag.overview_saved_undo),
  )
  LinkAction(
      stringResource(R.string.overview_saved_edit),
      color,
      onEdit,
      Modifier.testTag(C.Tag.overview_saved_edit),
  )
}

/** The Figma's entry row: 12 dp corners. */
private val ENTRY_CORNER = 12.dp

/** From this font scale (150%), the card's rows stack. */
private const val LARGE_FONT = 1.5f
