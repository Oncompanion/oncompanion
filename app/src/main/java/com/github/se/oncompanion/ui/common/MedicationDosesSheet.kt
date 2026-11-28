// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors
import kotlinx.coroutines.launch

/**
 * The sheet where the patient corrects how many doses of a medication taken several times a day
 * they took today (design: M3, "Doses"), on any screen where the patient ticks their doses. − and +
 * change the count shown, and only Done saves it: swiping the sheet down, Back or a tap outside
 * closes it without saving.
 *
 * @param sheet the medication and the count set so far
 * @param onLess one dose fewer, see [DosesSheet.withDraftChangedBy]
 * @param onMore one dose more, see [DosesSheet.withDraftChangedBy]
 * @param onDone saves the count and closes the sheet, once it has slid away
 * @param onClose closes it without saving
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedicationDosesSheet(
    sheet: DosesSheet,
    onLess: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  ModalBottomSheet(
      onDismissRequest = onClose,
      sheetState = sheetState,
      containerColor = MaterialTheme.lakeColors.stone.card,
      modifier = Modifier.testTag(C.Tag.overview_doses_sheet),
  ) {
    DosesSheetBody(
        sheet,
        onLess,
        onMore,
        onDone = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDone() } },
    )
  }
}

/**
 * What the sheet shows, under its handle: the medication, − and + around the count with its bar,
 * then Done. It scrolls with large text or a phone held sideways, so Done stays reachable.
 */
@Composable
internal fun DosesSheetBody(
    sheet: DosesSheet,
    onLess: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
  val name = sheet.medication.name
  Column(
      Modifier.verticalScroll(rememberScrollState())
          .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.lg),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
          name,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
          stringResource(
              R.string.overview_doses_sheet_subtitle,
              frequencyLabel(sheet.medication).orEmpty(),
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      RoundButton(
          R.drawable.ic_remove,
          stringResource(R.string.overview_dose_less, name),
          enabled = sheet.draft > 0,
          filled = false,
          onClick = onLess,
          modifier = Modifier.testTag(C.Tag.overview_doses_less),
      )
      Column(
          Modifier.weight(1f),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        Text(
            // A plural: "taken" can depend on the number in other languages
            pluralStringResource(
                R.plurals.overview_doses_sheet_count,
                sheet.draft,
                sheet.draft,
                sheet.doses,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Read again by TalkBack after each − or +
            modifier =
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(C.Tag.overview_doses_count),
        )
        DoseBar(sheet.doses, sheet.draft, Modifier.width(SHEET_BAR_WIDTH), SHEET_SEGMENT_HEIGHT)
      }
      RoundButton(
          R.drawable.ic_add,
          stringResource(R.string.overview_dose_more, name),
          enabled = sheet.draft < sheet.doses,
          filled = true,
          onClick = onMore,
          modifier = Modifier.testTag(C.Tag.overview_doses_more),
      )
    }
    LakeButton(
        text = stringResource(R.string.overview_doses_done),
        onClick = onDone,
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_doses_done),
    )
  }
}

/** The sheet's − (outlined) or + (filled), 48 dp, faded and ignoring taps at its limit. */
@Composable
private fun RoundButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Box(
      modifier
          .size(ROUND_BUTTON_SIZE)
          .alpha(if (enabled) 1f else LakeButtonDefaults.DISABLED_ALPHA)
          .clip(CircleShape)
          .then(
              if (filled) Modifier.background(colors.primary, CircleShape)
              else
                  Modifier.border(
                      ROUND_BUTTON_BORDER,
                      MaterialTheme.lakeColors.primaryText,
                      CircleShape,
                  )
          )
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = description },
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = if (filled) colors.onPrimary else MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.size(ROUND_ICON_SIZE),
    )
  }
}

/** The bar under the count, wider and thicker than the card's. */
private val SHEET_BAR_WIDTH = 132.dp
private val SHEET_SEGMENT_HEIGHT = 10.dp

/** The 48 dp − and + with their 24 dp icons. */
private val ROUND_BUTTON_SIZE = 48.dp
private val ROUND_ICON_SIZE = 24.dp

/** The outline of the − button, as on the card's "+". */
private val ROUND_BUTTON_BORDER = 1.5.dp
