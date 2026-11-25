// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.route
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardDetail
import com.github.se.oncompanion.ui.theme.cardEyebrow
import com.github.se.oncompanion.ui.theme.cardTitle
import com.github.se.oncompanion.ui.theme.full
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One thing a member of the care circle did for the patient, in the "Your circle" sheet
 * (US-50, #457; Figma: "Prescription states"): who and when, the prescription as it is now, and the
 * link to open it. A prescription still waiting for the patient is outlined in dawn, like the app's
 * other "look at this" content (DESIGN.md: check).
 *
 * @param onOpen opens this route, picked when the link is tapped by
 *   [com.github.se.oncompanion.ui.navigation.route] (#458): the review form while the prescription
 *   waits, its page otherwise
 */
@Composable
fun CircleNotificationCard(
    card: CircleCard,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val stone = MaterialTheme.lakeColors.stone
  val notification = card.notification
  val waiting = card.prescription?.confirmation == PrescriptionConfirmation.PENDING
  Surface(
      shape = MaterialTheme.shapes.large,
      color = stone.card,
      contentColor = colors.onSurface,
      border =
          if (waiting) BorderStroke(WAITING_BORDER, colors.secondary)
          else BorderStroke(CARD_BORDER, stone.cardBorder),
      modifier = modifier.fillMaxWidth().testTag(C.Tag.circleSheetCard(notification.id)),
  ) {
    Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
      Box(
          Modifier.size(BADGE).background(stone.badge, MaterialTheme.shapes.full),
          contentAlignment = Alignment.Center,
      ) {
        Icon(painterResource(R.drawable.ic_pill), contentDescription = null, tint = stone.onBadge)
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
          Initial(notification.actorFirstName)
          Text(
              text =
                  stringResource(
                      R.string.circle_sheet_added,
                      notification.actorFirstName,
                      card.time.format(TIME),
                  ),
              style = MaterialTheme.typography.cardEyebrow,
              color = colors.onSurfaceVariant,
          )
        }
        PrescriptionLines(card) { onOpen(notification.route(card.prescription)) }
      }
    }
  }
}

/**
 * The prescription as it is now: its prescriber and state, its medications, and its link while
 * there is something to open. A rejected one keeps its medications but has no link; a gone one says
 * what its page says.
 */
@Composable
private fun PrescriptionLines(card: CircleCard, onOpen: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val prescription = card.prescription
  if (prescription == null) {
    Text(
        text = stringResource(R.string.prescription_detail_not_found_title),
        style = MaterialTheme.typography.cardDetail,
        color = colors.onSurfaceVariant,
    )
    return
  }
  // The state at the end of the line, or under the name when both don't fit (large fonts)
  FlowRow(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      itemVerticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
        text =
            prescription.prescribedBy?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.circle_sheet_prescription),
        style = MaterialTheme.typography.cardTitle,
        modifier = Modifier.padding(end = Spacing.sm),
    )
    when (prescription.confirmation) {
      PrescriptionConfirmation.PENDING ->
          StatusChip(
              stringResource(R.string.circle_sheet_to_confirm),
              colors.secondaryContainer,
              colors.onSecondaryContainer,
          )
      PrescriptionConfirmation.CONFIRMED ->
          StatusChip(
              stringResource(R.string.circle_sheet_confirmed),
              colors.primaryContainer,
              colors.onPrimaryContainer,
          )
      PrescriptionConfirmation.REJECTED ->
          StatusChip(
              stringResource(R.string.circle_sheet_rejected),
              colors.errorContainer,
              colors.onErrorContainer,
          )
    }
  }
  Text(
      text = prescription.medications.joinToString(", ") { it.name },
      style = MaterialTheme.typography.cardDetail,
      color = colors.onSurfaceVariant,
  )
  // A rejected prescription was removed from the patient's: nothing to open
  if (prescription.confirmation != PrescriptionConfirmation.REJECTED) {
    LinkRow(
        stringResource(R.string.circle_sheet_see_prescription),
        onOpen,
        C.Tag.circleSheetOpen(card.notification.id),
    )
  }
}

/**
 * A member's initial on a small disc, as in the Figma's cards. Decorative: TalkBack skips it, as
 * the line next to it says the name.
 */
@Composable
private fun Initial(name: String) {
  // Sized in sp, so the disc grows with the letter at large font sizes
  val size = with(LocalDensity.current) { INITIAL.toDp() }
  Box(
      Modifier.size(size)
          .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.full),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = name.take(1).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.semantics { hideFromAccessibility() },
    )
  }
}

@Composable
private fun StatusChip(text: String, container: Color, content: Color) {
  Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = content,
      modifier =
          Modifier.background(container, MaterialTheme.shapes.full)
              .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
  )
}

/** A link with a chevron ("See prescription ›"), a full 48 dp touch target. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit, tag: String) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          Modifier.heightIn(min = LINK_HEIGHT)
              .clip(MaterialTheme.shapes.medium)
              .clickable(role = Role.Button, onClick = onClick)
              .testTag(tag),
  ) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.lakeColors.primaryText,
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
    )
  }
}

// The time as the Overview writes it; #558 will make every screen follow the chosen format
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

// The Figma's sizes
private val BADGE = 40.dp
private val INITIAL = 20.sp
private val CARD_BORDER = 1.dp
private val WAITING_BORDER = 1.5.dp
private val LINK_HEIGHT = 48.dp
