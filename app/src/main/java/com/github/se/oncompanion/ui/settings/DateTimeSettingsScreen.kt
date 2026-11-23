// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.settings.DateFormatPreference
import com.github.se.oncompanion.model.settings.TimeFormatPreference
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing

/** Collects the current formats for the summary and preview. */
@Composable
internal fun DateTimeSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onTimeFormat: () -> Unit,
    onDateFormat: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  DateTimeSettingsContent(
      state,
      rememberDateTimePreviews(),
      onBack,
      onTimeFormat,
      onDateFormat,
      viewModel::retry,
  )
}

/** Shows the saved formats and how they would display the same appointment. */
@Composable
internal fun DateTimeSettingsContent(
    state: SettingsUiState,
    previews: DateTimePreviews,
    onBack: () -> Unit,
    onTimeFormat: () -> Unit,
    onDateFormat: () -> Unit,
    onRetry: () -> Unit,
) {
  SettingsLayout(
      stringResource(R.string.settings_date_time),
      onBack,
      Modifier.testTag(C.Tag.settings_date_time_screen),
  ) {
    SettingsFeedback(state, onRetry)
    state.settings?.let {
      FormatHint(R.string.settings_date_time_intro)
      val time = previews.time(it.timeFormat)
      val date = previews.date(it.dateFormat)
      FormatCard {
        SettingsLinkRow(
            stringResource(R.string.settings_time_format),
            stringResource(R.string.settings_format_summary, timeFormatLabel(it.timeFormat), time),
            onTimeFormat,
            Modifier.testTag(C.Tag.settings_time_format),
        )
        SettingsLinkRow(
            stringResource(R.string.settings_date_format),
            if (it.dateFormat == DateFormatPreference.FOLLOW_DEVICE)
                stringResource(
                    R.string.settings_format_summary,
                    dateFormatLabel(it.dateFormat),
                    date,
                )
            else dateFormatLabel(it.dateFormat),
            onDateFormat,
            Modifier.testTag(C.Tag.settings_date_format),
        )
      }
      Text(
          stringResource(R.string.settings_format_preview),
          style = MaterialTheme.typography.titleMedium,
      )
      FormatCard {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
          Text(
              stringResource(R.string.settings_preview_appointment),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              stringResource(R.string.settings_preview_date_time, date, time),
              Modifier.testTag(C.Tag.settings_format_preview),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      FormatHint(R.string.settings_date_time_scope)
    }
  }
}

/** Saves time choices through the existing Settings ViewModel. */
@Composable
internal fun TimeFormatScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  TimeFormatContent(
      state,
      rememberDateTimePreviews(),
      onBack,
      viewModel::selectTimeFormat,
      viewModel::retry,
  )
}

@Composable
internal fun TimeFormatContent(
    state: SettingsUiState,
    previews: DateTimePreviews,
    onBack: () -> Unit,
    onSelect: (TimeFormatPreference) -> Unit,
    onRetry: () -> Unit,
) {
  SettingsLayout(
      stringResource(R.string.settings_time_format),
      onBack,
      Modifier.testTag(C.Tag.settings_time_format_screen),
  ) {
    SettingsFeedback(state, onRetry)
    state.settings?.let { settings ->
      FormatHint(R.string.settings_time_format_hint)
      FormatCard(Modifier.selectableGroup()) {
        TimeFormatPreference.entries.forEach { choice ->
          SettingsChoiceRow(
              timeFormatLabel(choice),
              formatExample(previews.time(choice), choice == TimeFormatPreference.FOLLOW_DEVICE),
              settings.timeFormat == choice,
              !state.saving,
              { onSelect(choice) },
              Modifier.testTag(C.Tag.settingsTimeChoice(choice.name)),
          )
        }
      }
    }
  }
}

/** Saves date choices through the existing Settings ViewModel. */
@Composable
internal fun DateFormatScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  DateFormatContent(
      state,
      rememberDateTimePreviews(),
      onBack,
      viewModel::selectDateFormat,
      viewModel::retry,
  )
}

@Composable
internal fun DateFormatContent(
    state: SettingsUiState,
    previews: DateTimePreviews,
    onBack: () -> Unit,
    onSelect: (DateFormatPreference) -> Unit,
    onRetry: () -> Unit,
) {
  SettingsLayout(
      stringResource(R.string.settings_date_format),
      onBack,
      Modifier.testTag(C.Tag.settings_date_format_screen),
  ) {
    SettingsFeedback(state, onRetry)
    state.settings?.let { settings ->
      FormatHint(R.string.settings_date_format_hint)
      FormatCard(Modifier.selectableGroup()) {
        DateFormatPreference.entries.forEach { choice ->
          SettingsChoiceRow(
              dateFormatLabel(choice),
              formatExample(previews.date(choice), choice == DateFormatPreference.FOLLOW_DEVICE),
              settings.dateFormat == choice,
              !state.saving,
              { onSelect(choice) },
              Modifier.testTag(C.Tag.settingsDateChoice(choice.name)),
          )
        }
      }
      FormatHint(R.string.settings_date_language_hint)
    }
  }
}

@Composable
internal fun dateFormatLabel(choice: DateFormatPreference): String =
    stringResource(
        when (choice) {
          DateFormatPreference.FOLLOW_DEVICE -> R.string.appearance_device
          DateFormatPreference.TEXT_DATE -> R.string.settings_date_text
          DateFormatPreference.DAY_MONTH_YEAR -> R.string.settings_date_dmy
          DateFormatPreference.YEAR_MONTH_DAY -> R.string.settings_date_ymd
        }
    )

@Composable
internal fun timeFormatLabel(choice: TimeFormatPreference): String =
    stringResource(
        when (choice) {
          TimeFormatPreference.FOLLOW_DEVICE -> R.string.appearance_device
          TimeFormatPreference.TWELVE_HOUR -> R.string.settings_time_12
          TimeFormatPreference.TWENTY_FOUR_HOUR -> R.string.settings_time_24
        }
    )

@Composable
private fun formatExample(value: String, followDevice: Boolean): String =
    if (followDevice) stringResource(R.string.settings_phone_format_example, value) else value

@Composable
private fun FormatHint(text: Int) {
  Text(
      stringResource(text),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun FormatCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column(modifier) { content() }
  }
}
