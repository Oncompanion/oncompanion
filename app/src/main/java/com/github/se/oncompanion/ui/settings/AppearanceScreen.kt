// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing

/** Collects appearance state while navigation remains owned by the host. */
@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  AppearanceContent(state, onBack, viewModel::selectAppearance, viewModel::retry)
}

/** Offers an accessible single choice using the app's existing palettes. */
@Composable
fun AppearanceContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAppearance: (AppearancePreference) -> Unit,
    onRetry: () -> Unit,
) {
  SettingsLayout(
      stringResource(R.string.settings_appearance),
      onBack,
      Modifier.testTag(C.Tag.appearance_screen),
  ) {
    SettingsFeedback(state, onRetry)
    if (state.settings != null) {
      Text(
          stringResource(R.string.appearance_intro),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
      ) {
        Column(Modifier.selectableGroup()) {
          AppearancePreference.entries.forEach { preference ->
            SettingsChoiceRow(
                appearanceLabel(preference),
                if (preference == AppearancePreference.FOLLOW_DEVICE)
                    stringResource(R.string.appearance_device_hint)
                else null,
                state.settings.appearance == preference,
                !state.saving,
                { onAppearance(preference) },
                Modifier.testTag(appearanceTag(preference)),
                selectedTag = C.Tag.appearance_selected,
            )
          }
        }
      }
      Text(
          stringResource(R.string.appearance_persistence),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Shared accessible single-choice row with optional supporting text. */
@Composable
internal fun SettingsChoiceRow(
    label: String,
    hint: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTag: String? = null,
) {
  Row(
      modifier
          .fillMaxWidth()
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
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
    if (selected) {
      Icon(
          Icons.Default.Check,
          contentDescription = null,
          modifier = if (selectedTag != null) Modifier.testTag(selectedTag) else Modifier,
      )
    }
  }
}

@Composable
internal fun appearanceLabel(preference: AppearancePreference): String =
    stringResource(
        when (preference) {
          AppearancePreference.FOLLOW_DEVICE -> R.string.appearance_device
          AppearancePreference.LIGHT -> R.string.appearance_light
          AppearancePreference.DARK -> R.string.appearance_dark
        }
    )

internal fun appearanceTag(preference: AppearancePreference): String =
    when (preference) {
      AppearancePreference.FOLLOW_DEVICE -> C.Tag.appearance_device
      AppearancePreference.LIGHT -> C.Tag.appearance_light
      AppearancePreference.DARK -> C.Tag.appearance_dark
    }

@Composable
internal fun ColumnScope.SettingsFeedback(state: SettingsUiState, onRetry: () -> Unit) {
  if (state.readFailed) {
    Text(stringResource(R.string.settings_read_error), color = MaterialTheme.colorScheme.error)
    TextButton(onRetry, Modifier.testTag(C.Tag.settings_retry)) {
      Text(stringResource(R.string.settings_retry))
    }
  } else if (state.settings == null) {
    CircularProgressIndicator(Modifier.testTag(C.Tag.settings_loading))
  }
  if (state.saveFailed)
      Text(
          stringResource(R.string.settings_save_error),
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(C.Tag.settings_save_error),
      )
}
