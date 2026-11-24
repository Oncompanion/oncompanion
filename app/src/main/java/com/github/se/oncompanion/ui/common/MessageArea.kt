// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centers a replacement message when it fits and scrolls when it overflows, so its action stays
 * reachable on short windows and at large font sizes (#490). The message supplies its own side
 * padding so it wraps consistently across screens. Use this only outside existing vertical scroll
 * containers.
 */
@Composable
internal fun MessageArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    Box(
        Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight),
        contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}
