// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LocalDateTimeFormats
import com.github.se.oncompanion.ui.common.rememberDateTimeFormats

/** Applies the observed display settings and system bars to the app and emulator journeys. */
@Composable
fun AppAppearanceRoot(
    activity: ComponentActivity,
    viewModel: AppAppearanceViewModel,
    content: @Composable () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val darkTheme =
      (state.appearance ?: AppearancePreference.FOLLOW_DEVICE).usesDarkTheme(isSystemInDarkTheme())
  SideEffect {
    activity.enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
        // Match AndroidX's default navigation scrims on API 28 and platform contrast on API 29+.
        navigationBarStyle =
            SystemBarStyle.auto(
                Color.argb(0xe6, 0xff, 0xff, 0xff),
                Color.argb(0x80, 0x1b, 0x1b, 0x1b),
            ) {
              darkTheme
            },
    )
  }
  AppAppearanceTheme(darkTheme) {
    val formats = rememberDateTimeFormats(state.dateFormat, state.timeFormat)
    CompositionLocalProvider(LocalDateTimeFormats provides formats) {
      Surface(
          modifier = Modifier.fillMaxSize().testTag(C.Tag.main_screen_container),
          color = MaterialTheme.colorScheme.background,
          content = content,
      )
    }
  }
}
