// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.carecircle.NewsDot
import com.github.se.oncompanion.ui.common.animationsEnabled
import com.github.se.oncompanion.ui.theme.full

/**
 * The caregiver header's three-people button that opens "Whose care?". While someone logged
 * something the caregiver hasn't seen, it has a dot; when a new entry arrives, a glow behind it
 * pulses [PULSES] times first, to draw the eye once without moving all the time.
 *
 * @param newsKey which entries are new ([com.github.se.oncompanion.ui.carecircle.WhoseCareUiState.
 *   newsKey]); `null` when none is
 * @param animate whether the glow may pulse; off with the system's Remove animations, leaving only
 *   the dot
 */
@Composable
internal fun WhoseCareButton(
    newsKey: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = animationsEnabled(),
) {
  // Saved, so turning the phone or coming back from the journal doesn't pulse again
  var pulsedFor by rememberSaveable { mutableStateOf<String?>(null) }
  var isPulsing by remember { mutableStateOf(false) }
  val glow = remember { Animatable(0f) }
  LaunchedEffect(newsKey, animate) {
    if (newsKey == null || newsKey == pulsedFor) return@LaunchedEffect
    // Before the pulse, so a rotation in the middle doesn't start it over
    pulsedFor = newsKey
    if (!animate) return@LaunchedEffect
    glow.snapTo(0f)
    isPulsing = true
    try {
      repeat(PULSES) {
        glow.animateTo(1f, tween(PULSE_MILLIS))
        glow.animateTo(0f, tween(PULSE_MILLIS))
      }
    } finally {
      // Also when cancelled by newer news or by leaving: never a glow stuck on
      isPulsing = false
    }
  }
  val glowColor = MaterialTheme.colorScheme.primary
  val surface = MaterialTheme.colorScheme.surface
  Box(modifier, contentAlignment = Alignment.Center) {
    if (isPulsing) {
      Box(
          Modifier.size(GLOW)
              .drawBehind { drawCircle(glowColor.copy(alpha = GLOW_ALPHA * glow.value)) }
              .testTag(C.Tag.overview_whose_care_glow)
      )
    }
    IconButton(onClick = onClick, modifier = Modifier.testTag(C.Tag.overview_whose_care)) {
      Icon(
          painterResource(R.drawable.ic_care_circle),
          contentDescription =
              stringResource(
                  if (newsKey != null) R.string.overview_switch_patient_new
                  else R.string.overview_switch_patient
              ),
      )
    }
    if (newsKey != null) {
      // Decorative: the button's description says there is something new
      NewsDot(
          DOT,
          Modifier.align(Alignment.TopEnd)
              .border(DOT_BORDER, surface, MaterialTheme.shapes.full)
              .testTag(C.Tag.overview_whose_care_dot),
      )
    }
  }
}

/** How many times the glow pulses when something new arrives: enough to be seen, then it stops. */
internal const val PULSES = 3

/** One way of a pulse, so a whole pulse (up and down) takes twice this. */
internal const val PULSE_MILLIS = 600

// As the patient's sailboat button: a 14 dp dot with a 2 dp edge; the glow is as wide as the button
private val DOT = 14.dp
private val DOT_BORDER = 2.dp
private val GLOW = 48.dp
private const val GLOW_ALPHA = 0.35f
