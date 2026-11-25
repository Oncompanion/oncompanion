// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.CardLink
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.cardChip
import com.github.se.oncompanion.ui.theme.cardDetail
import com.github.se.oncompanion.ui.theme.cardEyebrow
import com.github.se.oncompanion.ui.theme.cardTitle
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "10:00" */
private val nextUpTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** "Thu 8 Oct · 10:00", for a later day. The app's UI is in English. */
private val nextUpDayFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** How many medications the card lists before "N more ›", so the cards under it never move. */
internal const val MEDICATIONS_SHOWN = 2

/**
 * The patient's day on the Overview (Overview UX Refactor): "Next up" and "Today's medications", or
 * a single "Nothing planned today" card when there is neither.
 *
 * @param onTickDose ticks a dose of a medication, by its ID (see [OverviewViewModel.tickDose])
 * @param onOpenDoses opens the sheet to correct a medication's doses, by its ID
 * @param modifier for the first card, e.g. to overlap the header's lower edge
 */
@Composable
internal fun PatientDay(
    uiState: OverviewUiState,
    onOpenPlanning: () -> Unit,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTickDose: (String) -> Unit = {},
    onOpenDoses: (String) -> Unit = {},
) {
  val nextUp = uiState.nextUp
  val medications = uiState.todayMedications
  if (nextUp == null && medications.isEmpty()) {
    // Something was planned if today lists anything: it's all behind the patient by now
    NothingPlannedCard(uiState.todayEntries.isNotEmpty(), onOpenPlanning, modifier)
    return
  }
  if (nextUp != null) {
    NextUpCard(
        nextUp,
        uiState.now.toLocalDate(),
        onOpenPlanning = onOpenPlanning,
        onOpen = {
          if (nextUp.kind == TodayItemKind.EVENT) onOpenEvent(nextUp.id)
          else onOpenAppointment(nextUp.id)
        },
        modifier = modifier,
    )
  }
  if (medications.isNotEmpty()) {
    MedicationsCard(
        medications,
        onOpenPlanning,
        onTickDose,
        onOpenDoses,
        if (nextUp == null) modifier else Modifier,
    )
  }
}

/** A card of the day on the stone page, raised with the shared shadow. */
@Composable
internal fun DayCard(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
  val shape = MaterialTheme.shapes.large
  val stone = MaterialTheme.lakeColors.stone
  val border = cardBorder()
  val raised = modifier.fillMaxWidth().elevation1(shape)
  if (onClick == null)
      Surface(raised, shape = shape, color = stone.card, border = border, content = content)
  else
      Surface(
          onClick = onClick,
          modifier = raised,
          shape = shape,
          color = stone.card,
          border = border,
          content = content,
      )
}

/** The hairline around the patient's cards, so they stay distinct from the stone page by day. */
@Composable
internal fun cardBorder() =
    BorderStroke(CARD_BORDER_WIDTH, MaterialTheme.lakeColors.stone.cardBorder)

/** A card's icon, 18 dp, in a teal disc. */
@Composable
internal fun CardBadge(@DrawableRes icon: Int) {
  val stone = MaterialTheme.lakeColors.stone
  Badge(icon, BADGE_ICON_SIZE, stone.badge, stone.onBadge)
}

/**
 * The check-in's heart, 20 dp, in a disc of its own: teal by day, warm at night (Figma M3, Home ·
 * Dusk). Its own composable rather than a choice made from the icon, so no other card turns warm.
 */
@Composable
internal fun HeartBadge() {
  val stone = MaterialTheme.lakeColors.stone
  Badge(R.drawable.ic_heart, HEART_ICON_SIZE, stone.heartBadge, stone.onHeartBadge)
}

@Composable
private fun Badge(@DrawableRes icon: Int, iconSize: Dp, container: Color, content: Color) {
  Box(
      Modifier.size(BADGE_SIZE).background(container, CircleShape),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = content,
        modifier = Modifier.size(iconSize),
    )
  }
}

/** The small title at the top of a card ("Next up", "Today"): the Figma's 14/18 eyebrow. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
  Text(
      text,
      style = MaterialTheme.typography.cardEyebrow,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier,
  )
}

/**
 * "Next up": the event or appointment, when it starts (with its day if not today) and where.
 * Tapping it opens its page; "Planning ›" opens Planning.
 */
@Composable
internal fun NextUpCard(
    nextUp: NextUp,
    today: LocalDate,
    onOpenPlanning: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
  DayCard(modifier.testTag(C.Tag.overview_next_up), onClick = onOpen) {
    Row(
        Modifier.padding(
            start = Spacing.lg,
            end = Spacing.sm,
            top = Spacing.md,
            bottom = Spacing.md,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      CardBadge(R.drawable.ic_clock)
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Eyebrow(stringResource(R.string.overview_next_up), Modifier.weight(1f))
          CardLink(
              stringResource(R.string.overview_planning_link),
              onOpenPlanning,
              Modifier.testTag(C.Tag.overview_next_up_planning_link),
          )
        }
        Text(
            nextUp.title,
            style = MaterialTheme.typography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            nextUpWhen(nextUp, today),
            style = MaterialTheme.typography.cardDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** "10:00 · Wellness center" today; "Tomorrow, Sun 4 Oct · 10:00" or "Thu 8 Oct · 10:00" later. */
@Composable
private fun nextUpWhen(nextUp: NextUp, today: LocalDate): String {
  val start = nextUp.dateTime
  val time =
      when (start.toLocalDate()) {
        today -> start.format(nextUpTimeFormatter)
        today.plusDays(1) ->
            stringResource(R.string.overview_tomorrow_at, start.format(nextUpDayFormatter))
        else -> start.format(nextUpDayFormatter)
      }
  val place = nextUp.place?.takeIf { it.isNotBlank() } ?: return time
  return stringResource(R.string.overview_with_place, time, place)
}

/**
 * "Today's medications" with a link to Planning: the first [MEDICATIONS_SHOWN], each with its
 * recorded frequency and the doses ticked today, then "N more ›" to Planning on a line of its own.
 * More rows would push the cards under it and squeeze the lake header, so the count goes in the
 * title instead.
 *
 * @param onTickDose ticks the next dose of a medication, by its ID, or unticks one taken once a day
 * @param onOpenDoses opens the sheet to correct the doses of one taken several times a day
 */
@Composable
internal fun MedicationsCard(
    medications: List<TodayMedication>,
    onOpenPlanning: () -> Unit,
    onTickDose: (String) -> Unit,
    onOpenDoses: (String) -> Unit,
    modifier: Modifier,
) {
  DayCard(modifier.testTag(C.Tag.overview_medications)) {
    Row(
        Modifier.padding(
            start = Spacing.lg,
            end = Spacing.sm,
            top = Spacing.md,
            bottom = Spacing.md,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      CardBadge(R.drawable.ic_pill)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val hidden = medications.size - MEDICATIONS_SHOWN
        Row(verticalAlignment = Alignment.CenterVertically) {
          Eyebrow(
              if (hidden > 0)
                  stringResource(R.string.overview_today_medications_count, medications.size)
              else stringResource(R.string.overview_today_medications),
              Modifier.weight(1f),
          )
          CardLink(
              stringResource(R.string.overview_planning_link),
              onOpenPlanning,
              Modifier.testTag(C.Tag.overview_planning_link),
          )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
          medications.take(MEDICATIONS_SHOWN).forEach { MedicationRow(it, onTickDose, onOpenDoses) }
        }
        if (hidden > 0) MoreLink(hidden, onOpenPlanning)
      }
    }
  }
}

/** The recorded frequency ("Twice a day"), as a neutral chip. */
@Composable
internal fun FrequencyChip(text: String) {
  Text(
      text,
      style = MaterialTheme.typography.cardChip,
      color = MaterialTheme.colorScheme.onSurface,
      modifier =
          Modifier.background(MaterialTheme.lakeColors.stone.chip, CHIP_SHAPE)
              .padding(horizontal = Spacing.md, vertical = Spacing.xs),
  )
}

/**
 * "Today · Nothing planned today", when there is neither a next event nor a medication, or "Nothing
 * else today" when the day's plans have all started ([dayStarted]). Tapping it opens Planning,
 * where the day is filled in.
 */
@Composable
internal fun NothingPlannedCard(
    dayStarted: Boolean,
    onOpenPlanning: () -> Unit,
    modifier: Modifier,
) {
  DayCard(modifier.testTag(C.Tag.overview_nothing_planned), onClick = onOpenPlanning) {
    Row(
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      CardBadge(R.drawable.ic_clock)
      Column(Modifier.weight(1f)) {
        Eyebrow(stringResource(R.string.overview_today))
        Text(
            stringResource(
                if (dayStarted) R.string.overview_nothing_else_today
                else R.string.overview_nothing_today
            ),
            style = MaterialTheme.typography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(
                if (dayStarted) R.string.overview_nothing_else_today_hint
                else R.string.overview_nothing_today_hint
            ),
            style = MaterialTheme.typography.cardDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** The Figma's teal disc behind each card's icon, and the icon in it. */
private val BADGE_SIZE = 36.dp

/** The hairline around the patient's cards (Figma M3: 1 dp, ink at 8 %). */
private val CARD_BORDER_WIDTH = 1.dp
private val BADGE_ICON_SIZE = 18.dp

/** The heart is a little larger in its disc than the other icons. */
private val HEART_ICON_SIZE = 20.dp

/** A link or pill stays a 48 dp touch target, even where it looks smaller. */
internal val LINK_HEIGHT = 48.dp

/** The Figma's frequency chip: 14 dp corners. */
private val CHIP_SHAPE = RoundedCornerShape(14.dp)
