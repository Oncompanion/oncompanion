// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.theme.Spacing
import kotlin.math.roundToInt

/** Opens the device preferences; unfinished categories remain hidden. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onDateTime: () -> Unit,
    modifier: Modifier = Modifier,
    onLanguage: () -> Unit = {},
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val textSizePercent = (LocalConfiguration.current.fontScale * 100).roundToInt()
  val languageModel: LanguageViewModel = viewModel()
  val language by languageModel.uiState.collectAsStateWithLifecycle()
  LifecycleResumeEffect(languageModel) {
    languageModel.refresh()
    onPauseOrDispose {}
  }
  SettingsContent(
      state,
      onBack,
      onAppearance,
      viewModel::retry,
      onDateTime,
      modifier,
      language,
      onLanguage,
      textSizePercent = textSizePercent,
      onTextSize = { openTextSizeSettings(context) },
  )
}

/** Displays only implemented categories and their persisted values. */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onRetry: () -> Unit,
    onDateTime: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.FOLLOW_DEVICE,
    onLanguage: () -> Unit = {},
    textSizePercent: Int = 100,
    onTextSize: () -> Unit = {},
) {
  SettingsLayout(
      stringResource(R.string.settings_title),
      onBack,
      modifier.testTag(C.Tag.settings_screen),
  ) {
    SettingsFeedback(state, onRetry)
    state.settings?.let { settings ->
      Text(
          stringResource(R.string.settings_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
      ) {
        Column {
          SettingsLinkRow(
              stringResource(R.string.settings_appearance),
              appearanceLabel(settings.appearance),
              onAppearance,
              Modifier.testTag(C.Tag.settings_appearance),
          )
          Text(
              stringResource(R.string.appearance_phone_scope),
              modifier = Modifier.padding(horizontal = Spacing.lg),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          SettingsLinkRow(
              stringResource(R.string.settings_date_time),
              if (
                  settings.timeFormat == TimeFormatPreference.FOLLOW_DEVICE &&
                      settings.dateFormat == DateFormatPreference.FOLLOW_DEVICE
              )
                  stringResource(R.string.appearance_device)
              else
                  stringResource(
                      R.string.settings_format_summary,
                      timeFormatLabel(settings.timeFormat),
                      dateFormatLabel(settings.dateFormat),
                  ),
              onDateTime,
              Modifier.testTag(C.Tag.settings_date_time),
          )
        }
      }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
      SettingsLinkRow(
          stringResource(R.string.settings_language),
          languageLabel(language),
          onLanguage,
          Modifier.testTag(C.Tag.settings_language),
      )
    }
    Text(
        stringResource(R.string.settings_accessibility),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
      SettingsLinkRow(
          stringResource(R.string.settings_text_size),
          stringResource(R.string.settings_text_size_percent, textSizePercent),
          onTextSize,
          Modifier.testTag(C.Tag.settings_text_size),
      )
    }
  }
}

/** Opens a preference and shows its currently confirmed value. */
@Composable
internal fun SettingsLinkRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier.fillMaxWidth().clickable(onClick = onClick).padding(Spacing.lg),
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(
          value,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
  }
}

private const val TEXT_READING_SETTINGS = "android.settings.TEXT_READING_SETTINGS"

private fun openTextSizeSettings(context: Context) {
  // Older Android versions and some manufacturers expose font size under Accessibility.
  for (action in listOf(TEXT_READING_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS)) {
    try {
      context.startActivity(Intent(action))
      return
    } catch (_: ActivityNotFoundException) {
      // Try the next Settings destination.
    }
  }
  Toast.makeText(context, R.string.settings_text_size_unavailable, Toast.LENGTH_SHORT).show()
}

/** Shares the lake header and scrollable body between settings destinations. */
@Composable
internal fun SettingsLayout(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
      modifier = modifier,
      topBar = {
        LakeTopAppBar(
            title,
            navigationIcon = {
              IconButton(onClick = onBack, modifier = Modifier.testTag(C.Tag.settings_back)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back))
              }
            },
        )
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        content = content,
    )
  }
}
