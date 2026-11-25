// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Routes the selected option to Android's app locale storage. */
@Composable
internal fun LanguageScreen(onBack: () -> Unit, viewModel: LanguageViewModel = viewModel()) {
  val selected by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleResumeEffect(viewModel) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  LanguageContent(selected, onBack, viewModel::select)
}

/** Displays the choices and, in French, the sample appointment from the design. */
@Composable
internal fun LanguageContent(
    selected: AppLanguage,
    onBack: () -> Unit,
    onSelect: (AppLanguage) -> Unit,
) {
  SettingsLayout(
      stringResource(R.string.settings_language),
      onBack,
      Modifier.testTag(C.Tag.language_screen),
  ) {
    Text(
        stringResource(R.string.language_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
      Column(Modifier.selectableGroup()) {
        AppLanguage.entries.forEach { language ->
          val tag =
              when (language) {
                AppLanguage.FOLLOW_DEVICE -> C.Tag.language_device
                AppLanguage.ENGLISH -> C.Tag.language_english
                AppLanguage.FRENCH -> C.Tag.language_french
              }
          SettingsChoiceRow(
              label = languageLabel(language),
              hint =
                  if (language == AppLanguage.FOLLOW_DEVICE)
                      stringResource(R.string.language_device_hint)
                  else null,
              selected = selected == language,
              enabled = true,
              onSelect = { onSelect(language) },
              modifier = Modifier.testTag(tag),
          )
        }
      }
    }
    Text(
        stringResource(R.string.language_user_data_scope),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val locale = LocalConfiguration.current.locales[0]
    if (locale?.language == Locale.FRENCH.language) FrenchLanguagePreview(locale)
  }
}

@Composable
private fun FrenchLanguagePreview(locale: Locale) {
  val sampleDate = LocalDateTime.of(2026, 11, 18, 14, 30)
  val dateLabel = sampleDate.format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", locale))

  Text(stringResource(R.string.language_preview), style = MaterialTheme.typography.titleMedium)
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(Spacing.lg)) {
      Text(
          stringResource(R.string.language_preview_appointment),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          dateLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun languageLabel(language: AppLanguage): String =
    stringResource(
        when (language) {
          AppLanguage.FOLLOW_DEVICE -> R.string.language_device
          AppLanguage.ENGLISH -> R.string.language_english
          AppLanguage.FRENCH -> R.string.language_french
        }
    )
