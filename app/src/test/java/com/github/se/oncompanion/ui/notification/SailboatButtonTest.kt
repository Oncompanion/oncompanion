// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Tests of [SailboatButton]. */
@RunWith(AndroidJUnit4::class)
class SailboatButtonTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private var badge by mutableStateOf(SailboatBadge.NONE)
  private var taps = 0

  private fun show() {
    compose.setContent { OncompanionTheme { SailboatButton(badge, onClick = { taps++ }) } }
  }

  private val dot
    get() = compose.onNodeWithTag(C.Tag.circle_button_dot, useUnmergedTree = true)

  private val add
    get() = compose.onNodeWithTag(C.Tag.circle_button_add, useUnmergedTree = true)

  @Test
  fun eachBadge_saysWhatItShows_andATapOpens() {
    show()
    val button = compose.onNodeWithTag(C.Tag.circle_button)

    button.assertContentDescriptionEquals(context.getString(R.string.circle_button))
    dot.assertDoesNotExist()
    add.assertDoesNotExist()

    badge = SailboatBadge.NEW
    button.assertContentDescriptionEquals(context.getString(R.string.circle_button_new))
    dot.assertIsDisplayed()
    add.assertDoesNotExist()

    badge = SailboatBadge.ADD
    button.assertContentDescriptionEquals(context.getString(R.string.circle_button_add))
    add.assertIsDisplayed()
    dot.assertDoesNotExist()

    button
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        .assertWidthIsAtLeast(48.dp)
        .assertHeightIsAtLeast(48.dp)
        .performClick()
    assertEquals(1, taps)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun theRing_showsThereIsACircle_andNotWhileNobodyIsInIt() {
    show()
    val button = compose.onNodeWithTag(C.Tag.circle_button)
    // On the halo, at the top of the disc, and in the corner the disc doesn't reach
    fun ring() = button.colorAt(24.dp, 1.5.dp)
    fun outside() = button.colorAt(1.dp, 1.dp)

    for (shown in listOf(SailboatBadge.NONE, SailboatBadge.NEW)) {
      badge = shown
      assertNotEquals("$shown has its ring", outside(), ring())
    }
    badge = SailboatBadge.ADD
    assertEquals("nobody yet: no ring", outside(), ring())
  }
}
