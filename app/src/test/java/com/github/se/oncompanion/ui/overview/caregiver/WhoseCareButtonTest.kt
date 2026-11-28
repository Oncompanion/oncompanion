// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests of [WhoseCareButton]. */
@RunWith(AndroidJUnit4::class)
class WhoseCareButtonTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** A whole pulse, up and down. */
  private val pulse = 2L * PULSE_MILLIS

  private fun glow() = compose.onNodeWithTag(C.Tag.overview_whose_care_glow)

  private fun dot() = compose.onNodeWithTag(C.Tag.overview_whose_care_dot)

  @Test
  fun nothingNew_noDot_noGlow_andItOpensWhoseCare() {
    var clicks = 0
    compose.setContent { WhoseCareButton(newsKey = null, onClick = { clicks++ }, animate = true) }

    dot().assertDoesNotExist()
    glow().assertDoesNotExist()
    compose
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertContentDescriptionEquals("Choose whose care to follow")
        .performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun somethingNew_glowsAFewTimes_thenOnlyTheDotStays() {
    compose.mainClock.autoAdvance = false
    compose.setContent { WhoseCareButton(newsKey = "anne/a1", onClick = {}, animate = true) }
    compose.mainClock.advanceTimeByFrame()

    compose
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertContentDescriptionEquals("Choose whose care to follow, something new")
    dot().assertExists()
    // Still pulsing just before the last pulse ends, gone after it
    compose.mainClock.advanceTimeBy(PULSES * pulse - PULSE_MILLIS)
    glow().assertExists()
    compose.mainClock.advanceTimeBy(pulse)
    glow().assertDoesNotExist()
    dot().assertExists()
  }

  @Test
  fun withRemoveAnimations_onlyTheDot() {
    compose.mainClock.autoAdvance = false
    compose.setContent { WhoseCareButton(newsKey = "anne/a1", onClick = {}, animate = false) }
    compose.mainClock.advanceTimeByFrame()

    glow().assertDoesNotExist()
    dot().assertExists()
  }

  @Test
  fun theSameNews_doesntGlowAgainAfterARotation_butNewerNewsDoes() {
    var newsKey by mutableStateOf<String?>("anne/a1")
    val show = {
      compose.activity.setContent { WhoseCareButton(newsKey, onClick = {}, animate = true) }
    }
    compose.runOnUiThread(show)
    compose.mainClock.advanceTimeBy(PULSES * pulse + pulse)
    glow().assertDoesNotExist()

    // The activity is recreated, as when the phone turns
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread(show)
    compose.mainClock.autoAdvance = false
    compose.mainClock.advanceTimeBy(PULSE_MILLIS / 2L)
    glow().assertDoesNotExist()
    dot().assertExists()

    newsKey = "anne/a2"
    // A key set from the test reaches the button at the next recomposition
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(PULSE_MILLIS / 2L)
    glow().assertExists()
  }
}
