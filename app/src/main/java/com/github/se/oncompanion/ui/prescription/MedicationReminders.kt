// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.ReminderTimes
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.ClickableReadOnlyField
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.TimePickerDialog
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Reminder times as the form and the summary show them: "09:00". */
internal val reminderTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * What the patient does with a medication's reminders in the prescription form (US-32).
 *
 * @property onRemindChange turns them on, at suggested times, or off
 * @property onTimeChange moves the reminder at an index to a time
 * @property onAddTime adds one, when the frequency lets the patient choose how many
 * @property onRemoveTime removes the one at an index
 */
data class ReminderActions(
    val onRemindChange: (Boolean) -> Unit = {},
    val onTimeChange: (Int, LocalTime) -> Unit = { _, _ -> },
    val onAddTime: () -> Unit = {},
    val onRemoveTime: (Int) -> Unit = {},
)

/**
 * The reminders of the open medication (US-32, Figma M3 "Reminders · in the form"): Remind me, then
 * one time per dose, each opening a time picker. Only the patient chooses them: a caregiver sees
 * that the patient will, after checking the prescription ([caregiverOf]).
 *
 * @param caregiverOf the patient's first name when a caregiver fills in the form; `null` for the
 *   patient
 */
@Composable
internal fun MedicationReminders(
    medication: MedicationDraft,
    actions: ReminderActions,
    caregiverOf: String?,
) {
  Column(
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
      modifier = Modifier.testTag(C.Tag.medication_reminders),
  ) {
    Text(
        text = stringResource(R.string.medication_reminders_section).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (caregiverOf != null) {
      ReminderNote(stringResource(R.string.medication_reminders_by_patient, caregiverOf))
      return@Column
    }
    val times = medication.reminderTimes
    RemindMe(times, actions.onRemindChange)
    if (times.isEmpty()) return@Column

    val canRemove = ReminderTimes.canRemove(times, medication.frequency)
    times.forEachIndexed { index, time ->
      ReminderTimeField(
          index = index,
          time = time,
          // The same time twice can't be saved: the second one says so
          isRepeated = time in times.take(index),
          canRemove = canRemove,
          actions = actions,
      )
    }
    if (ReminderTimes.canAdd(times, medication.frequency)) {
      LakeButton(
          text = stringResource(R.string.medication_reminders_add_time),
          onClick = actions.onAddTime,
          style = LakeButtonStyle.Outlined,
          icon = rememberVectorPainter(Icons.Default.Add),
          modifier = Modifier.testTag(C.Tag.medication_reminders_add_time),
      )
    }
    ReminderNote(stringResource(R.string.medication_reminders_only_help))
  }
}

/** The switch that turns the reminders on or off, with how many a day once on. */
@Composable
private fun RemindMe(times: List<LocalTime>, onRemindChange: (Boolean) -> Unit) {
  val on = times.isNotEmpty()
  // The row handles the tap, so the switch only shows the state
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .toggleable(value = on, role = Role.Switch, onValueChange = onRemindChange)
              .testTag(C.Tag.medication_reminders_switch),
  ) {
    Column(Modifier.weight(1f)) {
      Text(
          stringResource(R.string.medication_reminders_remind_me),
          style = MaterialTheme.typography.titleSmall,
      )
      Text(
          text =
              if (on) {
                pluralStringResource(R.plurals.medication_reminders_a_day, times.size, times.size)
              } else stringResource(R.string.medication_reminders_off),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = on, onCheckedChange = null)
  }
}

/** One reminder time; tapping it opens a time picker. */
@Composable
private fun ReminderTimeField(
    index: Int,
    time: LocalTime,
    isRepeated: Boolean,
    canRemove: Boolean,
    actions: ReminderActions,
) {
  var pickerOpen by rememberSaveable { mutableStateOf(false) }
  val label = stringResource(R.string.medication_reminders_time, index + 1)
  Row(verticalAlignment = Alignment.CenterVertically) {
    ClickableReadOnlyField(
        value = time.format(reminderTimeFormatter),
        label = label,
        onClick = { pickerOpen = true },
        onClickLabel = stringResource(R.string.medication_reminders_change_time),
        trailingIcon = { Icon(painterResource(R.drawable.ic_schedule), contentDescription = null) },
        supportingText =
            if (isRepeated) {
              {
                Text(
                    stringResource(
                        R.string.medication_reminders_repeated,
                        time.format(reminderTimeFormatter),
                    )
                )
              }
            } else null,
        isError = isRepeated,
        modifier = Modifier.weight(1f).testTag(C.Tag.medicationReminderTime(index)),
    )
    if (canRemove) {
      IconButton(
          onClick = { actions.onRemoveTime(index) },
          modifier = Modifier.testTag(C.Tag.medicationReminderRemove(index)),
      ) {
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.medication_reminders_remove_time, label),
        )
      }
    }
  }
  if (pickerOpen) {
    TimePickerDialog(
        time = time,
        onConfirm = {
          actions.onTimeChange(index, it)
          pickerOpen = false
        },
        onDismiss = { pickerOpen = false },
        confirmLabel = stringResource(R.string.medication_reminders_picker_confirm),
        cancelLabel = stringResource(R.string.medication_reminders_picker_cancel),
        // As the form shows times (HH:mm), whatever the phone's setting
        is24Hour = true,
        testTags =
            PickerTestTags(
                picker = C.Tag.medication_reminders_picker,
                confirm = C.Tag.medication_reminders_picker_confirm,
                cancel = C.Tag.medication_reminders_picker_cancel,
            ),
    )
  }
}

/** A short line under the reminders, with the info icon. */
@Composable
private fun ReminderNote(text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Icon(
        painterResource(R.drawable.ic_info),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
