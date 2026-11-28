// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tests of [CardLink]. */
@RunWith(AndroidJUnit4::class)
class CardLinkTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  // Real text widths: the default graphics mode measures text as 0 wide
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(fontScale = 2f)
  fun aLabelThatJustFitsItsLine_keepsTheChevron() {
    var clicks = 0
    var chevron = 0f
    compose.setContent {
      OncompanionTheme {
        // Just wide enough for the words on one line, not for the chevron after them, as in a
        // "Your circle" card on a small phone at 200%
        val words = rememberTextMeasurer().measure(LABEL, MaterialTheme.typography.labelLarge)
        val density = LocalDensity.current
        chevron = with(density) { CHEVRON_SIZE.toPx() }
        Box(Modifier.width(with(density) { words.size.width.toDp() } + 2.dp)) {
          CardLink(LABEL, { clicks++ }, Modifier.testTag("link"))
        }
      }
    }

    val link = compose.onNodeWithTag("link").fetchSemanticsNode().boundsInRoot
    val words = compose.onNodeWithText(LABEL, useUnmergedTree = true).fetchSemanticsNode()
    assertTrue("room for the chevron", link.right - words.boundsInRoot.right >= chevron)

    compose.onNodeWithTag("link").performClick()
    assertEquals(1, clicks)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun aLabelThatFits_keepsTheLinkAsWideAsItsWords() {
    compose.setContent {
      OncompanionTheme {
        Box(Modifier.width(300.dp)) { CardLink("Planning", {}, Modifier.testTag("link")) }
      }
    }
    // "Planning ›" on the Overview: the link doesn't stretch to the width it is given
    val link = compose.onNodeWithTag("link").fetchSemanticsNode()
    assertTrue(link.size.width < with(link.layoutInfo.density) { 300.dp.toPx() })
  }

  private companion object {
    const val LABEL = "See prescription"
  }
}
