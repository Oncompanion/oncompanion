// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.ClickableReadOnlyField
import com.github.se.oncompanion.ui.common.DayPickerDialog
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.TimePickerDialog
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which picker of the "When" field is open. */
internal enum class WhenPicker {
  NONE,
  DATE,
  TIME,
}

/**
 * The day and time, "Today" and the current time until the user changes them. Tapping it picks a
 * day, then a time.
 */
@Composable
internal fun WhenField(
    uiState: AddSymptomUiState,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
  var picker by rememberSaveable { mutableStateOf(WhenPicker.NONE) }
  val today = uiState.now.toLocalDate()
  val date = uiState.date ?: today
  val time = uiState.time ?: uiState.now.toLocalTime()

  ClickableReadOnlyField(
      value =
          stringResource(
              R.string.symptom_add_date_time_value,
              dayLabel(date, today),
              time.format(symptomTimeFormatter),
          ),
      label = stringResource(R.string.symptom_add_date_time),
      onClick = { picker = WhenPicker.DATE },
      onClickLabel = stringResource(R.string.symptom_add_change_date_time),
      trailingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) },
      // The error if the time is later than now, else the hint while the time is still "now"
      supportingText =
          when {
            uiState.isInFuture -> {
              { Text(stringResource(R.string.symptom_add_in_future)) }
            }
            uiState.isNow -> {
              { Text(stringResource(R.string.symptom_add_date_time_hint)) }
            }
            else -> null
          },
      isError = uiState.isInFuture,
      modifier = Modifier.testTag(C.Tag.symptom_add_when),
  )
  WhenPickers(picker, { picker = it }, uiState, onDateChange, onTimeChange)
}

/**
 * The pickers behind a "When" field, shared by the form and the check-in sheet: the day first, then
 * the time; cancelling the time keeps the confirmed day.
 *
 * @param picker the picker open, if any
 * @param onPickerChange opens the next picker, or closes them with [WhenPicker.NONE]
 */
@Composable
internal fun WhenPickers(
    picker: WhenPicker,
    onPickerChange: (WhenPicker) -> Unit,
    uiState: AddSymptomUiState,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
  val today = uiState.now.toLocalDate()
  when (picker) {
    WhenPicker.DATE ->
        DayPickerDialog(
            date = uiState.date ?: today,
            onConfirm = {
              onDateChange(it)
              onPickerChange(WhenPicker.TIME)
            },
            onDismiss = { onPickerChange(WhenPicker.NONE) },
            confirmLabel = stringResource(R.string.symptom_add_picker_confirm),
            cancelLabel = stringResource(R.string.symptom_add_picker_cancel),
            testTags =
                PickerTestTags(
                    picker = C.Tag.symptom_add_date_picker,
                    confirm = C.Tag.symptom_add_picker_confirm,
                    cancel = C.Tag.symptom_add_picker_cancel,
                ),
            // A symptom can't be logged in advance
            latestDate = today,
        )
    WhenPicker.TIME ->
        TimePickerDialog(
            time = uiState.time ?: uiState.now.toLocalTime(),
            onConfirm = {
              onTimeChange(it)
              onPickerChange(WhenPicker.NONE)
            },
            onDismiss = { onPickerChange(WhenPicker.NONE) },
            confirmLabel = stringResource(R.string.symptom_add_picker_confirm),
            cancelLabel = stringResource(R.string.symptom_add_picker_cancel),
            // As the symptom screens show times (HH:mm), whatever the phone's setting
            is24Hour = true,
            testTags =
                PickerTestTags(
                    picker = C.Tag.symptom_add_time_picker,
                    confirm = C.Tag.symptom_add_picker_confirm,
                    cancel = C.Tag.symptom_add_picker_cancel,
                ),
        )
    WhenPicker.NONE -> {
      // No picker is open: nothing to show
    }
  }
}

/**
 * Keeps "now" current while the form or the check-in sheet is on screen, for the time shown and the
 * check that the chosen time isn't later: refreshes when it comes back, then each time a new minute
 * starts. Stops while it's hidden.
 */
@Composable
internal fun KeepNowCurrent(viewModel: AddSymptomViewModel) {
  val scope = rememberCoroutineScope()
  LifecycleResumeEffect(viewModel) {
    val ticker = scope.launch {
      while (true) {
        delay(viewModel.refreshNow().toMillis())
      }
    }
    onPauseOrDispose { ticker.cancel() }
  }
}

/**
 * "Which symptom?", the name of the "Other" symptom, required to save it (Figma: "Check-in · Other,
 * not named yet"). A name of the list says to pick that symptom instead.
 */
@Composable
internal fun OtherNameField(uiState: AddSymptomUiState, onOtherNameChange: (String) -> Unit) {
  val listedAs = uiState.otherNameListedAs
  LakeTextField(
      value = uiState.otherName,
      onValueChange = onOtherNameChange,
      placeholder = { Text(stringResource(R.string.symptom_add_other_name_placeholder)) },
      supportingText =
          listedAs?.let {
            {
              Text(stringResource(R.string.symptom_add_other_name_listed, stringResource(it.label)))
            }
          },
      isError = listedAs != null,
      singleLine = true,
      keyboardOptions =
          KeyboardOptions(
              capitalization = KeyboardCapitalization.Sentences,
              imeAction = ImeAction.Done,
          ),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_add_other_name),
  )
}

/** Free text. */
@Composable
internal fun NotesField(uiState: AddSymptomUiState, onNotesChange: (String) -> Unit) {
  LakeTextField(
      value = uiState.notes,
      onValueChange = onNotesChange,
      label = { Text(stringResource(R.string.symptom_add_notes)) },
      maxLines = 6,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_add_notes),
  )
}

/** The message shown for each saving error. */
internal fun AddSymptomError.messageRes(): Int =
    when (this) {
      AddSymptomError.NOT_SIGNED_IN -> R.string.symptom_add_error_not_signed_in
      AddSymptomError.SAVE_FAILED -> R.string.symptom_add_error_save_failed
    }
