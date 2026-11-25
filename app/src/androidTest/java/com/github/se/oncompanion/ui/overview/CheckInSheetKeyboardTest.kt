// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.symptom.AddSymptomUiState
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.FileInputStream
import java.time.LocalDateTime
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The check-in sheet with a real keyboard, in its own window as on the phone: the sheet keeps its
 * area above the keyboard, and its content scrolls there, so the note being typed in and Save can
 * both be brought above the keyboard. Robolectric can't show this: it has no keyboard, and the
 * sheet's window doesn't make room for one it draws itself.
 */
@RunWith(AndroidJUnit4::class)
class CheckInSheetKeyboardTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** The device's setting, restored after the test, so the next tests see the device unchanged. */
  private var showImeWithHardKeyboard: String? = null

  private fun shell(command: String): String =
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
          descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim()
      }

  @Before
  fun showTheKeyboard_evenWithAHardwareOne() {
    // An emulator with a hardware keyboard shows no keyboard on screen otherwise
    showImeWithHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
    shell("settings put secure show_ime_with_hard_keyboard 1")
  }

  @After
  fun restoreTheSetting() {
    val previous = showImeWithHardKeyboard?.takeUnless { it == "null" || it.isEmpty() }
    if (previous == null) shell("settings delete secure show_ime_with_hard_keyboard")
    else shell("settings put secure show_ime_with_hard_keyboard $previous")
  }

  private val nausea =
      AddSymptomUiState(
          now = LocalDateTime.of(2026, 10, 3, 9, 30),
          types = listOf(SymptomType.NAUSEA),
          strengths = mapOf(SymptomType.NAUSEA to 4),
          strengthOf = SymptomType.NAUSEA,
      )

  /** Where the keyboard starts on the screen, in px, or `null` while it's hidden. */
  private fun keyboardTop(): Int? {
    var top: Int? = null
    onView(isRoot())
        .inRoot(isDialog())
        .perform(
            object : ViewAction {
              override fun getConstraints(): Matcher<View> = isRoot()

              override fun getDescription() = "read where the keyboard starts"

              override fun perform(uiController: UiController, view: View) {
                val keyboard =
                    ViewCompat.getRootWindowInsets(view)
                        ?.getInsets(WindowInsetsCompat.Type.ime())
                        ?.bottom ?: 0
                val onScreen = IntArray(2).also(view::getLocationOnScreen)
                top = if (keyboard > 0) onScreen[1] + view.height - keyboard else null
              }
            }
        )
    return top
  }

  private fun assertNoteAndSaveAboveTheKeyboard() {
    compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
    compose.setContent { OncompanionTheme { CheckInSheet(nausea, CheckInActions()) } }
    compose.onNodeWithTag(C.Tag.overview_check_in_sheet_note).performScrollTo().performClick()
    compose.waitUntil(KEYBOARD_TIMEOUT) { keyboardTop() != null }

    listOf(C.Tag.overview_check_in_sheet_note, C.Tag.overview_check_in_sheet_save).forEach { tag ->
      val node = compose.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
      val bottom = node.positionOnScreen.y + node.size.height
      val keyboard = checkNotNull(keyboardTop()) { "the keyboard closed" }
      assertTrue(
          "$tag ends at $bottom px, under the keyboard starting at $keyboard px",
          bottom <= keyboard,
      )
    }
  }

  @Test fun keyboardOpenOnTheNote_keepsItAndSaveAboveIt() = assertNoteAndSaveAboveTheKeyboard()

  @Test
  fun landscape_keyboardOpenOnTheNote_keepsItAndSaveAboveIt() {
    compose.runOnUiThread {
      compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    compose.waitUntil(KEYBOARD_TIMEOUT) {
      compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    assertNoteAndSaveAboveTheKeyboard()
  }

  private companion object {
    // The emulator can take several seconds to show its first keyboard
    const val KEYBOARD_TIMEOUT = 15_000L
  }
}
