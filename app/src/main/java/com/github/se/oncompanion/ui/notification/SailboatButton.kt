// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.full
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The sailboat in the patient's Overview header that opens the "Your circle" sheet (design: M3,
 * "Boat button"). While there is a circle it sits in a faint ring, with a dot when something in the
 * sheet is unseen; while nobody is in it, a "+" invites the patient to add someone, without the
 * ring. Screen readers say which.
 */
@Composable
fun SailboatButton(badge: SailboatBadge, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  val description =
      stringResource(
          when (badge) {
            SailboatBadge.NONE -> R.string.circle_button
            SailboatBadge.NEW -> R.string.circle_button_new
            SailboatBadge.ADD -> R.string.circle_button_add
          }
      )
  val ringed = badge != SailboatBadge.ADD
  val halo = colors.primary.copy(alpha = HALO_ALPHA)
  // Not clipped, so the badges can sit over the disc's edge; the ripple stays round instead
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier
              .size(TOUCH_TARGET)
              .clickable(
                  interactionSource = null,
                  indication = ripple(bounded = false, radius = TOUCH_TARGET / 2),
                  role = Role.Button,
                  onClick = onClick,
              )
              .semantics { contentDescription = description }
              .testTag(C.Tag.circle_button)
              // The faint halo around the disc while there is a circle
              .drawBehind {
                if (ringed) {
                  drawCircle(halo, radius = DISC.toPx() / 2, style = Stroke(HALO_WIDTH.toPx()))
                }
              },
  ) {
    Surface(
        shape = MaterialTheme.shapes.full,
        color = colors.primaryContainer,
        border = if (ringed) BorderStroke(EDGE_WIDTH, colors.primary) else null,
        modifier = Modifier.size(DISC),
    ) {
      // Decorative: the button's description says what it is
      Icon(
          painterResource(R.drawable.ic_sailboat),
          contentDescription = null,
          tint = MaterialTheme.lakeColors.onHorizon,
          modifier = Modifier.size(DISC),
      )
    }
    when (badge) {
      SailboatBadge.NONE -> Unit
      SailboatBadge.NEW ->
          Box(
              Modifier.align(Alignment.BottomEnd)
                  .size(DOT)
                  .border(BADGE_BORDER, colors.surface, MaterialTheme.shapes.full)
                  .background(colors.primary, MaterialTheme.shapes.full)
                  .testTag(C.Tag.circle_button_dot)
          )
      SailboatBadge.ADD ->
          Box(
              contentAlignment = Alignment.Center,
              modifier =
                  Modifier.align(Alignment.BottomEnd)
                      // It sits 2 dp past the touch target's corner
                      .offset(x = ADD_OVERHANG, y = ADD_OVERHANG)
                      .size(ADD)
                      .border(BADGE_BORDER, colors.surface, MaterialTheme.shapes.full)
                      .background(colors.primary, MaterialTheme.shapes.full)
                      .testTag(C.Tag.circle_button_add),
          ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(ADD_ICON),
            )
          }
    }
  }
}

// The sizes: a 48 dp touch target around a 44 dp disc, a 14 dp dot and a 20 dp "+"
private val TOUCH_TARGET = 48.dp
private val DISC = 44.dp
private val HALO_WIDTH = 5.dp
private const val HALO_ALPHA = 0.18f
private val EDGE_WIDTH = 1.5.dp
private val DOT = 14.dp
private val ADD = 20.dp
private val ADD_ICON = 14.dp
private val ADD_OVERHANG = 2.dp
private val BADGE_BORDER = 2.dp
