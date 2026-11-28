// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.LARGE_FONT_SCALE
import com.github.se.oncompanion.ui.common.StoneCard
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardItem
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One entry of the Planning list (Figma R3 · Parts): its time on the left, then a card with the
 * kind's disc, the title and a line saying where it stands. An appointment or event says "In 2 h",
 * "Happening now · until 15:00" or nothing, and is muted once over; a medication says its recorded
 * frequency.
 *
 * At a large font the time goes above the card, as on the Overview, so the title keeps its width.
 *
 * @param now the time the status is worked out at; the screen moves it on each minute
 * @param isToday the agenda shows today: a medication's time says "Today"
 * @param onClick opens the entry's page, or `null` when it has none
 */
@Composable
internal fun PlanningRow(
    item: PlanningItem,
    now: Instant,
    zoneId: ZoneId,
    timeFormatter: DateTimeFormatter,
    isToday: Boolean,
    onClick: (() -> Unit)?,
) {
  val timed = item.timing as? PlanningTiming.Timed
  val status = timed?.rowStatus(now, zoneId)
  val time = timed?.instant?.atZone(zoneId)?.format(timeFormatter)
  val timeText = time ?: stringResource(R.string.planning_today).takeIf { isToday }
  val timeColor =
      if (status.isSoon() || (timed == null && isToday)) MaterialTheme.lakeColors.primaryText
      else MaterialTheme.colorScheme.onSurfaceVariant
  val timeLabel =
      @Composable { modifier: Modifier ->
        timeText?.let {
          Text(it, modifier, color = timeColor, style = MaterialTheme.typography.labelLarge)
        }
      }
  val card =
      @Composable { modifier: Modifier ->
        RowCard(item, status, timeFormatter, zoneId, onClick, modifier)
      }
  // At a large font the time goes above the card (R4 · 200%), so a long title isn't squeezed
  if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      timeLabel(Modifier)
      card(Modifier)
    }
  } else {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Box(Modifier.widthIn(min = TIME_COLUMN_WIDTH)) { timeLabel(Modifier) }
      card(Modifier.weight(1f))
    }
  }
}

/** The card: disc, title, status line, who added it, then a chevron when it opens a page. */
@Composable
private fun RowCard(
    item: PlanningItem,
    status: PlanningRowStatus?,
    timeFormatter: DateTimeFormatter,
    zoneId: ZoneId,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
  val past = status == PlanningRowStatus.Past
  StoneCard(modifier) {
    Row(
        Modifier.testTag(C.Tag.planningItem(item.key))
            .then(
                if (onClick == null) Modifier.semantics(mergeDescendants = true) {}
                else Modifier.clickable(onClick = onClick)
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      KindDisc(item.source, past)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            item.title,
            style = MaterialTheme.typography.cardItem,
            color =
                if (past) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
        )
        if (item.source is PlanningSource.Medication) MedicationLine(item.frequency)
        else StatusLine(status, item.subtitle, timeFormatter, zoneId)
        // An entry a caregiver added says who did (US-41), and a prescription whether the patient
        // confirmed it (US-43)
        item.addedByName?.let { AddedBy(it, isConfirmed = item.isConfirmed) }
      }
      if (onClick != null) {
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CHEVRON_SIZE),
        )
      }
    }
  }
}

/**
 * The kind's icon in its color (appointment violet, event dawn, medication teal); gray once over.
 */
@Composable
private fun KindDisc(source: PlanningSource, past: Boolean) {
  val colors = MaterialTheme.colorScheme
  val (icon, label, container, content) =
      when (source) {
        is PlanningSource.Appointment ->
            DiscStyle(
                R.drawable.ic_calendar_month,
                R.string.planning_type_appointment,
                colors.tertiaryContainer,
                colors.onTertiaryContainer,
            )
        is PlanningSource.Event ->
            DiscStyle(
                R.drawable.ic_event,
                R.string.planning_type_event,
                colors.secondaryContainer,
                colors.onSecondaryContainer,
            )
        is PlanningSource.Medication ->
            DiscStyle(
                R.drawable.ic_medication,
                R.string.planning_type_medication,
                colors.primaryContainer,
                colors.onPrimaryContainer,
            )
      }
  Box(
      Modifier.size(DISC_SIZE)
          .background(if (past) colors.surfaceContainerHigh else container, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(icon),
        contentDescription = stringResource(label),
        tint = if (past) colors.onSurfaceVariant else content,
        modifier = Modifier.size(DISC_ICON_SIZE),
    )
  }
}

private data class DiscStyle(
    val icon: Int,
    val label: Int,
    val container: Color,
    val content: Color,
)

/**
 * "In 2 h · Cabinet Rive" or "Happening now · until 15:00", the state in teal; just the detail on a
 * later day or once over.
 */
@Composable
private fun StatusLine(
    status: PlanningRowStatus?,
    detail: String?,
    timeFormatter: DateTimeFormatter,
    zoneId: ZoneId,
) {
  val state =
      when (status) {
        is PlanningRowStatus.InMinutes ->
            pluralStringResource(
                R.plurals.planning_in_minutes,
                status.minutes.toInt(),
                status.minutes,
            )
        is PlanningRowStatus.InHours ->
            pluralStringResource(R.plurals.planning_in_hours, status.hours.toInt(), status.hours)
        is PlanningRowStatus.HappeningNow -> stringResource(R.string.planning_happening_now)
        else -> null
      }
  val after =
      (status as? PlanningRowStatus.HappeningNow)?.let {
        stringResource(R.string.planning_until, it.until.atZone(zoneId).format(timeFormatter))
      } ?: detail?.takeIf { it.isNotBlank() }
  val text =
      when {
        state != null && after != null ->
            stringResource(R.string.planning_status_detail, state, after)
        else -> state ?: after
      } ?: return
  val teal = MaterialTheme.lakeColors.primaryText
  val live = status is PlanningRowStatus.HappeningNow
  val style =
      MaterialTheme.typography.bodyMedium.copy(
          textIndent = if (live) TextIndent(firstLine = LIVE_DOT_INDENT) else TextIndent.None
      )
  val measurer = rememberTextMeasurer()
  BoxWithConstraints {
    // When "state · detail" doesn't fit on one line, the detail goes on the next one, without the
    // "·" left hanging at the end of the first. Measured before drawing, so no frame shows that
    val width = constraints.maxWidth
    val wraps =
        remember(text, state, width, style, measurer) {
          measurer
              .measure(
                  highlighted(text, state, teal),
                  style,
                  constraints = Constraints(maxWidth = width),
              )
              .lineCount > 1
        }
    if (wraps && state != null && after != null) {
      Column {
        StatusText(state, state, style)
        StatusText(after, null, style.copy(textIndent = TextIndent.None))
      }
    } else {
      StatusText(text, state, style)
    }
    // The live dot of Happening now (R3: teal, without a halo), centered on the first line
    if (live) {
      val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
      Box(
          Modifier.padding(top = (lineHeight - LIVE_DOT_SIZE) / 2)
              .size(LIVE_DOT_SIZE)
              .background(teal, CircleShape)
      )
    }
  }
}

/** [text] with [state] in teal wherever it is in it, so a reordered translation still works. */
private fun highlighted(text: String, state: String?, teal: Color): AnnotatedString =
    buildAnnotatedString {
      append(text)
      state
          ?.let { text.indexOf(it) }
          ?.takeIf { it >= 0 }
          ?.let { start ->
            addStyle(
                SpanStyle(color = teal, fontWeight = FontWeight.SemiBold),
                start,
                start + checkNotNull(state).length,
            )
          }
    }

/** One line of a row's status in [style], with [state] in teal (see [highlighted]). */
@Composable
private fun StatusText(text: String, state: String?, style: TextStyle) {
  Text(
      highlighted(text, state, MaterialTheme.lakeColors.primaryText),
      style = style,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** A medication's frequency as recorded, never read into times or doses. */
@Composable
private fun MedicationLine(frequency: String?) {
  Text(
      frequency?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.planning_frequency, it) }
          ?: stringResource(R.string.planning_frequency_unspecified),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** Whether the row says it is coming up today or under way, so its time is teal. */
private fun PlanningRowStatus?.isSoon(): Boolean =
    this is PlanningRowStatus.InMinutes ||
        this is PlanningRowStatus.InHours ||
        this is PlanningRowStatus.HappeningNow

/** The column of times, so the cards line up (DESIGN.md: at least 52 dp, growing with the text). */
private val TIME_COLUMN_WIDTH = 52.dp

/** The kind's disc (R3: 36 dp) and its icon. */
private val DISC_SIZE = 36.dp
private val DISC_ICON_SIZE = 18.dp

/** The chevron of an entry that opens a page. */
private val CHEVRON_SIZE = 20.dp

/** The live dot before "Happening now", and the room the first line leaves it. */
private val LIVE_DOT_SIZE = 8.dp
private val LIVE_DOT_INDENT = 14.sp
