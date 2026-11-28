// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * An off-white card on the stone page (DESIGN.md, Overview): the card color, a hairline so it stays
 * distinct from the page by day, and the shared shadow. Used for the sections of a form laid out on
 * the stone page, such as the prescription form.
 *
 * @param shape the card's corners: the form sections use Medium
 */
@Composable
fun StoneCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable () -> Unit,
) {
  val stone = MaterialTheme.lakeColors.stone
  Surface(
      modifier = modifier.fillMaxWidth().elevation1(shape),
      shape = shape,
      color = stone.card,
      border = BorderStroke(HAIRLINE, stone.cardBorder),
      content = content,
  )
}

/** The hairline around a stone card, as on the Overview's cards. */
private val HAIRLINE = 1.dp
