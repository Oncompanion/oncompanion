// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.symptom.icon
import com.github.se.oncompanion.ui.symptom.label
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The check-in's tiles, in the Figma's order (M3 page, Overview → Patient → Home): five symptoms in
 * one tap, then "More…" (`null`) for the others.
 */
private val checkInTiles =
    listOf(
        SymptomType.FATIGUE,
        SymptomType.NAUSEA,
        SymptomType.PAIN,
        SymptomType.HEADACHE,
        SymptomType.FEVER,
        null,
    )

/**
 * "How do you feel?" (Overview UX Refactor): the patient logs a symptom in one tap, as often as
 * they like during the day. A tile opens the symptom form with that symptom picked, "More…" opens
 * it empty, and "Journal ›" opens the journal. "Speak how you feel" under the tiles (US-73) lets
 * them say it instead.
 *
 * @param onLogSymptom opens the symptom form with that symptom picked, or empty for `null`
 * @param onSpeak opens the symptom form already listening; `null` when the phone can't listen, so
 *   there is no button
 */
@Composable
internal fun CheckInDock(
    onLogSymptom: (SymptomType?) -> Unit,
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
    onSpeak: (() -> Unit)? = null,
) {
  val shape = MaterialTheme.shapes.extraLarge
  Surface(
      modifier = modifier.fillMaxWidth().elevation1(shape).testTag(C.Tag.overview_check_in),
      shape = shape,
      color = MaterialTheme.lakeColors.stone.dock,
      border = cardBorder(),
  ) {
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
      CheckInHeader(onOpenJournal)
      SymptomTiles(checkInTiles, onLogSymptom)
      // The card's last row, as wide as the tiles (Figma: Overview → Speak instead → Home)
      if (onSpeak != null) {
        LakeButton(
            text = stringResource(R.string.overview_speak),
            onClick = onSpeak,
            style = LakeButtonStyle.Tonal,
            icon = painterResource(R.drawable.ic_mic),
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_speak),
        )
      }
    }
  }
}

/**
 * The heart, "How do you feel?", then "Journal ›", in one row while the title fits beside them on
 * two lines at most, each word whole. Otherwise (large font, small screen) the title gets its own
 * line and "Journal ›" goes under it, without the heart, as in the Figma at 200%.
 */
@Composable
private fun CheckInHeader(onOpenJournal: () -> Unit) {
  val title = stringResource(R.string.overview_check_in_title)
  val style = MaterialTheme.typography.titleLarge
  val measurer = rememberTextMeasurer()
  // Measured again only when the title, its style or the font size change
  val widestWord =
      remember(title, style, measurer) {
        title.split(" ").maxOf { measurer.measure(it, style).size.width }
      }
  val gap = Spacing.sm
  Layout(
      content = {
        HeartBadge()
        Text(title, style = style, color = MaterialTheme.colorScheme.onSurface)
        JournalLink(onOpenJournal)
      }
  ) { measurables, constraints ->
    val (badgeMeasurable, titleMeasurable, actionsMeasurable) = measurables
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val badge = badgeMeasurable.measure(loose)
    val actions = actionsMeasurable.measure(loose)
    val width = constraints.maxWidth
    val titleWidth = width - badge.width - actions.width - 2 * gap.roundToPx()
    val fitsBeside =
        titleWidth >= widestWord &&
            measurer
                .measure(title, style, constraints = Constraints(maxWidth = titleWidth))
                .lineCount <= MAX_TITLE_LINES
    if (fitsBeside) {
      // One row, everything centered on the title
      val text = titleMeasurable.measure(loose.copy(minWidth = titleWidth, maxWidth = titleWidth))
      val height = maxOf(badge.height, text.height, actions.height)
      layout(width, height) {
        badge.place(0, (height - badge.height) / 2)
        text.place(badge.width + gap.roundToPx(), (height - text.height) / 2)
        actions.place(width - actions.width, (height - actions.height) / 2)
      }
    } else {
      // The title on its own line, the actions under it
      val text = titleMeasurable.measure(loose)
      layout(width, text.height + actions.height) {
        text.place(0, 0)
        actions.place(0, text.height)
      }
    }
  }
}

/** "Journal ›", a 48 dp target opening the symptom journal. */
@Composable
private fun JournalLink(onOpenJournal: () -> Unit) {
  Row(
      Modifier.heightIn(min = 48.dp)
          .clip(MaterialTheme.shapes.medium)
          .clickable(role = Role.Button, onClick = onOpenJournal)
          .padding(start = Spacing.xs)
          .testTag(C.Tag.overview_journal_link),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(
        stringResource(R.string.overview_journal_link),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.lakeColors.primaryText,
    )
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.lakeColors.primaryText,
        modifier = Modifier.size(18.dp),
    )
  }
}

/**
 * The [tiles] ("More…" for `null`) in rows of the same width: three per row as in the Figma, all in
 * one row when the screen is wide (landscape) and they fit, and fewer per row when a word of a
 * label wouldn't fit (small screen or large font), so a word is never broken. Shared by the
 * check-in and its sheet.
 *
 * @param picked the symptoms picked in the sheet, each tile then showing whether it is; `null` on
 *   the check-in, whose tiles only open the symptom form
 * @param tagOf the test tag of each tile
 */
@Composable
internal fun SymptomTiles(
    tiles: List<SymptomType?>,
    onTap: (SymptomType?) -> Unit,
    picked: List<SymptomType>? = null,
    tagOf: (SymptomType?) -> String = { type ->
      type?.let { C.Tag.overviewCheckIn(it.name) } ?: C.Tag.overview_check_in_more
    },
) {
  val labels = tiles.map { stringResource(it?.label ?: R.string.overview_check_in_more) }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // A label can wrap between its words ("Loss of / appetite"), never inside one. Measured again
    // only when the labels, their style or the font size change
    val widestWord =
        remember(labels, style, density) {
          val words = labels.flatMap { it.split(" ") }
          with(density) { words.maxOf { measurer.measure(it, style).size.width }.toDp() }
        }
    val fits = { perRow: Int ->
      (maxWidth - TILE_SPACING * (perRow - 1)) / perRow - TILE_PADDING * 2 >= widestWord
    }
    val candidates = if (maxWidth >= WIDE) listOf(labels.size, 3, 2) else listOf(3, 2)
    val perRow = candidates.firstOrNull(fits) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
      tiles.zip(labels).chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
          row.forEach { (type, label) ->
            SymptomTile(
                label = label,
                icon = type?.icon ?: R.drawable.ic_symptom_more,
                onClick = { onTap(type) },
                isPicked = type?.let { picked?.contains(it) },
                modifier = Modifier.weight(1f).testTag(tagOf(type)),
            )
          }
          // A shorter last row keeps the tiles the same width as above
          repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

/**
 * A symptom tile: its icon above its label. Every symptom looks the same on purpose, so none seems
 * more serious (Figma "Symptom tile"). In the sheet ([isPicked] not `null`), a picked tile takes
 * the dawn colour and a check, the others an empty ring, so the state doesn't rely on colour alone.
 */
@Composable
private fun SymptomTile(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    isPicked: Boolean?,
    modifier: Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val picked = isPicked == true
  val content: @Composable () -> Unit = {
    Box(contentAlignment = Alignment.Center) {
      Column(
          Modifier.fillMaxWidth().padding(TILE_PADDING),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
      ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
      }
      if (isPicked != null) {
        PickedMark(picked, Modifier.align(Alignment.TopEnd).testTag(C.Tag.overview_tile_mark))
      }
    }
  }
  val shape = MaterialTheme.shapes.large
  val color = if (picked) colors.secondaryContainer else MaterialTheme.lakeColors.stone.tile
  val contentColor = if (picked) colors.onSecondaryContainer else colors.onSurface
  val border = BorderStroke(PICKED_BORDER, colors.secondary).takeIf { picked }
  val sized = modifier.heightIn(min = TILE_HEIGHT)
  if (isPicked == null) {
    Surface(onClick, sized, shape = shape, color = color, contentColor = contentColor) { content() }
  } else {
    // Read as a checkbox by TalkBack: "Nausea, checked"
    Surface(
        checked = picked,
        onCheckedChange = { onClick() },
        modifier = sized,
        shape = shape,
        color = color,
        contentColor = contentColor,
        border = border,
    ) {
      content()
    }
  }
}

/** The check of a picked tile, or the empty ring of one that isn't (Figma "Check"). */
@Composable
private fun PickedMark(picked: Boolean, modifier: Modifier) {
  if (picked) {
    Icon(
        painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = modifier.padding(Spacing.xs).size(18.dp),
    )
  } else {
    Box(
        modifier
            .padding(Spacing.sm)
            .size(16.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = RING_ALPHA), CircleShape)
    )
  }
}

/** The Figma's tile: 80 dp tall at least (it grows with a large font), 8 dp apart. */
private val TILE_HEIGHT = 80.dp
private val TILE_SPACING = Spacing.sm
private val TILE_PADDING = Spacing.xs

/** The border of a picked tile, and how faint the ring of one that isn't (Figma). */
private val PICKED_BORDER = 1.5.dp
private const val RING_ALPHA = 0.55f

/** Beyond this, the title squeezed beside the heart and "Journal ›" gets its own line instead. */
private const val MAX_TITLE_LINES = 2

/** From this width (a phone held sideways), the six tiles fit in one row. */
private val WIDE = 600.dp
