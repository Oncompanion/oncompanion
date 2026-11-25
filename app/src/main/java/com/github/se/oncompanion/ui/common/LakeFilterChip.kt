// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.theme.Spacing

/**
 * The Figma "Filter chip": 48 dp tall, a full touch target (Material's chip is 32 dp), with Medium
 * corners. White with an Outline border, or dawn without it once [selected]. There is no check, so
 * picking a chip doesn't make it wider and push the chips after it along.
 *
 * @param onClick toggles the chip; `null` shows a read-only chip (e.g. what was logged, on a
 *   detail): the same colours, but it can't be tapped nor is it announced as a checkbox
 * @param role what screen readers call it: a checkbox when several chips can be picked, as on
 *   Material's chip, or [Role.RadioButton] when only one can
 * @param widestLabel the longest [label] the chip can show, if it changes: the chip is always as
 *   wide as it, so a longer label doesn't push the chips after it along or onto another line
 */
@Composable
fun LakeFilterChip(
    selected: Boolean,
    onClick: (() -> Unit)?,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    widestLabel: String? = null,
) {
  val colors = MaterialTheme.colorScheme
  val color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest
  val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
  val border = if (selected) null else BorderStroke(1.dp, colors.outline)
  if (onClick == null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color,
        contentColor = contentColor,
        border = border,
        modifier = modifier,
    ) {
      ChipLabel(label, widestLabel)
    }
    return
  }
  Surface(
      selected = selected,
      onClick = onClick,
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      color = color,
      contentColor = contentColor,
      border = border,
      modifier =
          modifier
              .semantics { this.role = role }
              .alpha(if (enabled) 1f else LakeButtonDefaults.DISABLED_ALPHA),
  ) {
    ChipLabel(label, widestLabel)
  }
}

/**
 * The label, 16 dp from each side whether or not the chip is picked, and as wide as [widestLabel]
 * laid out unseen and unread beside it.
 */
@Composable
private fun ChipLabel(label: String, widestLabel: String?) {
  Box(
      Modifier.heightIn(min = 48.dp).padding(horizontal = Spacing.lg),
      contentAlignment = Alignment.Center,
  ) {
    if (widestLabel != null) {
      Text(
          widestLabel,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.alpha(0f).clearAndSetSemantics {},
      )
    }
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}
