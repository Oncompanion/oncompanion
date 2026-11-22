// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardItem
import com.github.se.oncompanion.ui.theme.full
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * A medication's name, then its frequency as a chip and, when its doses can be ticked, a bar with
 * one segment per dose and the round button at the end of the row. Taken once a day, the row and
 * the button toggle the dose; several times a day, the button ticks the next one and "1 of 3" says
 * where the patient is.
 */
@Composable
internal fun MedicationRow(medication: TodayMedication, onTickDose: (String) -> Unit) {
  val doses = medication.dosesPerDay
  val tick = { onTickDose(medication.medicationId) }
  val label = doseButtonLabel(medication)
  Row(
      Modifier.testTag(C.Tag.overviewMedication(medication.key))
          .then(
              if (doses == 1)
                  Modifier.clickable(onClickLabel = label, role = Role.Button) { tick() }
              else Modifier
          ),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Text(
          medication.name,
          style = MaterialTheme.typography.cardItem,
          color = MaterialTheme.colorScheme.onSurface,
      )
      DosesLine(medication)
    }
    if (doses != null) DoseButton(medication, label, tick)
  }
}

/**
 * The frequency chip, the bar and "1 of 3" (only when there are several doses a day). With large
 * text they don't fit on one line: the bar and the count go under the chip.
 */
@Composable
private fun DosesLine(medication: TodayMedication) {
  if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      medication.frequency?.let { FrequencyChip(it) }
      medication.dosesPerDay?.let { DoseBar(it, medication.taken, Modifier.fillMaxWidth()) }
      DosesTaken(medication)
    }
  } else {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      medication.frequency?.let { FrequencyChip(it) }
      medication.dosesPerDay?.let { DoseBar(it, medication.taken, Modifier.weight(1f)) }
      DosesTaken(medication)
    }
  }
}

/** "1 of 3", then "All taken", for a medication taken several times a day; nothing otherwise. */
@Composable
private fun DosesTaken(medication: TodayMedication) {
  val doses = medication.dosesPerDay ?: return
  if (doses == 1) return
  val allTaken = medication.taken >= doses
  Text(
      if (allTaken) stringResource(R.string.overview_doses_all_taken)
      else stringResource(R.string.overview_doses_taken, medication.taken, doses),
      style = MaterialTheme.typography.bodyMedium,
      color =
          if (allTaken) MaterialTheme.lakeColors.primaryText
          else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** One segment per dose of the day, the first [taken] filled; the button says it to TalkBack. */
@Composable
private fun DoseBar(doses: Int, taken: Int, modifier: Modifier) {
  Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    repeat(doses) { index ->
      Box(
          Modifier.weight(1f)
              .height(SEGMENT_HEIGHT)
              .background(
                  if (index < taken) MaterialTheme.colorScheme.primary
                  else MaterialTheme.lakeColors.stone.chip,
                  MaterialTheme.shapes.full,
              )
      )
    }
  }
}

/**
 * The round button at the end of a row: "+" until every dose is taken, then a filled check. Taken
 * once a day, the check unticks the dose; several times a day, it is disabled once all are taken,
 * until the sheet to correct the doses comes.
 */
@Composable
private fun DoseButton(medication: TodayMedication, label: String, onClick: () -> Unit) {
  val doses = medication.dosesPerDay ?: return
  val allTaken = medication.taken >= doses
  val disc =
      if (allTaken) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
      else Modifier.border(DOSE_DISC_BORDER, MaterialTheme.lakeColors.primaryText, CircleShape)
  Box(
      Modifier.size(DOSE_BUTTON_SIZE)
          .clip(CircleShape)
          .clickable(enabled = !allTaken || doses == 1, role = Role.Button, onClick = onClick)
          .semantics { contentDescription = label }
          .testTag(C.Tag.overviewDoseButton(medication.key)),
      contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.size(DOSE_DISC_SIZE).then(disc), contentAlignment = Alignment.Center) {
      Icon(
          painterResource(if (allTaken) R.drawable.ic_check else R.drawable.ic_add),
          contentDescription = null,
          tint =
              if (allTaken) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.lakeColors.primaryText,
          modifier = Modifier.size(DOSE_ICON_SIZE),
      )
    }
  }
}

/** What the dose button does and where the patient is, for TalkBack. */
@Composable
private fun doseButtonLabel(medication: TodayMedication): String {
  val doses = medication.dosesPerDay ?: return ""
  val name = medication.name
  return when {
    doses == 1 && medication.taken >= 1 -> stringResource(R.string.overview_dose_untick, name)
    doses == 1 -> stringResource(R.string.overview_dose_tick_once, name)
    medication.taken >= doses -> stringResource(R.string.overview_doses_all_taken_of, name)
    // A plural: "taken" can depend on the number in other languages
    else ->
        pluralStringResource(
            R.plurals.overview_dose_tick,
            medication.taken,
            name,
            medication.taken,
            doses,
        )
  }
}

/**
 * "2 more ›" under the list, opening Planning where every medication of the day is listed. It looks
 * like "Planning ›" and keeps a 48 dp touch target.
 */
@Composable
internal fun MoreLink(count: Int, onOpenPlanning: () -> Unit) {
  Row(
      Modifier.heightIn(min = LINK_HEIGHT)
          .clip(MaterialTheme.shapes.medium)
          .clickable(role = Role.Button, onClick = onOpenPlanning)
          .testTag(C.Tag.overview_more_medications),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
  ) {
    Text(
        pluralStringResource(R.plurals.overview_more_medications, count, count),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.lakeColors.primaryText,
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.size(CHEVRON_SIZE),
    )
  }
}

/** From this font scale, the bar and the count go under the frequency chip (Figma M3, 200%). */
private const val LARGE_TEXT_SCALE = 1.5f

/** The Figma's dose segments: 6 dp high, fully rounded. */
private val SEGMENT_HEIGHT = 6.dp

/** The dose button's 48 dp touch target, around the Figma's 36 dp disc and 20 dp icon. */
private val DOSE_BUTTON_SIZE = 48.dp
private val DOSE_DISC_SIZE = 36.dp
private val DOSE_ICON_SIZE = 20.dp

/** The outline of the "+" disc. */
private val DOSE_DISC_BORDER = 1.5.dp
