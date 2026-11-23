// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing

/** Routes the selected option to Android's app locale storage. */
@Composable
internal fun LanguageScreen(onBack: () -> Unit) {
  var selected by remember { mutableStateOf(AppLanguage.current()) }
  LanguageContent(selected, onBack) { language ->
    language.apply()
    // Choosing the phone's current language can leave the configuration identical.
    selected = language
  }
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
              onClick = { onSelect(language) },
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
    if (LocalConfiguration.current.locales[0]?.language == "fr") FrenchLanguagePreview()
  }
}

@Composable
private fun FrenchLanguagePreview() {
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
          stringResource(R.string.language_preview_date),
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

/** Retains the same radio selection layout in the Appearance and Language settings. */
@Composable
internal fun SettingsChoiceRow(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    checkModifier: Modifier = Modifier,
) {
  Row(
      modifier
          .fillMaxWidth()
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
          .padding(Spacing.lg),
      horizontalArrangement = Arrangement.spacedBy(Spacing.md),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.titleMedium)
      if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = checkModifier)
  }
}
