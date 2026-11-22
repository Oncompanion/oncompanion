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
 * Centers a replacement message in its bounded content area and keeps it reachable on short
 * windows. Screens whose content already scrolls can place their message in that scroll container.
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
