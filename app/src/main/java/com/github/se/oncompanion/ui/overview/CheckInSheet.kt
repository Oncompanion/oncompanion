// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.symptom.AddSymptomUiState
import com.github.se.oncompanion.ui.symptom.AddSymptomViewModel
import com.github.se.oncompanion.ui.symptom.KeepNowCurrent
import com.github.se.oncompanion.ui.symptom.OtherNameField
import com.github.se.oncompanion.ui.symptom.SymptomStrengths
import com.github.se.oncompanion.ui.symptom.WhenPicker
import com.github.se.oncompanion.ui.symptom.WhenPickers
import com.github.se.oncompanion.ui.symptom.dayLabel
import com.github.se.oncompanion.ui.symptom.messageRes
import com.github.se.oncompanion.ui.symptom.symptomTimeFormatter
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime

/** Every symptom, in the form's order: the sheet offers them all (Figma: "Check-in sheet"). */
private val sheetTiles: List<SymptomType?> = SymptomType.entries

/**
 * The check-in's sheet (#544, Figma M3: "Check-in · One symptom / Two symptoms"), shown over the
 * Overview while [viewModel] logs a new entry. Saving closes it; so do ✕, Back, a swipe down or a
 * tap outside, without saving, except while the entry is being saved.
 *
 * @param onSaved called once the entry is saved, to close the sheet
 * @param onClose closes the sheet without saving
 */
@Composable
internal fun CheckInSheetHost(
    viewModel: AddSymptomViewModel,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(uiState.save) { if (uiState.save == SavePhase.SAVED) currentOnSaved() }
  // "Now, 09:30" and the check that the time chosen isn't later follow the clock
  KeepNowCurrent(viewModel)
  CheckInSheet(
      uiState = uiState,
      actions =
          CheckInActions(
              onToggleType = viewModel::toggleType,
              onSelectStrengthOf = viewModel::selectStrengthOf,
              onStrengthChange = viewModel::onStrengthChange,
              onClearStrength = viewModel::clearStrength,
              onDateChange = viewModel::onDateChange,
              onTimeChange = viewModel::onTimeChange,
              onOtherNameChange = viewModel::onOtherNameChange,
              onNotesChange = viewModel::onNotesChange,
              onSave = viewModel::save,
              onClose = onClose,
          ),
  )
}

/** What the patient can do in the sheet, each sent to [AddSymptomViewModel] or the Overview. */
internal data class CheckInActions(
    val onToggleType: (SymptomType) -> Unit = {},
    val onSelectStrengthOf: (SymptomType) -> Unit = {},
    val onStrengthChange: (Int) -> Unit = {},
    val onClearStrength: () -> Unit = {},
    val onDateChange: (LocalDate) -> Unit = {},
    val onTimeChange: (LocalTime) -> Unit = {},
    val onOtherNameChange: (String) -> Unit = {},
    val onNotesChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The sheet, stateless so each state can be tested without a ViewModel. It can't be closed while
 * the entry is being saved: the save would finish behind it with nothing to show where it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckInSheet(uiState: AddSymptomUiState, actions: CheckInActions) {
  val canClose = uiState.save == SavePhase.EDITING
  val currentCanClose by rememberUpdatedState(canClose)
  val sheetState =
      rememberModalBottomSheetState(
          skipPartiallyExpanded = true,
          confirmValueChange = { it != SheetValue.Hidden || currentCanClose },
      )
  ModalBottomSheet(
      onDismissRequest = { if (currentCanClose) actions.onClose() },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      // A phone held sideways gives the handle's room to the note and Save above the keyboard; ✕
      // and a swipe down still close the sheet
      dragHandle = if (isCompactHeight()) null else ({ BottomSheetDefaults.DragHandle() }),
      modifier = Modifier.testTag(C.Tag.overview_check_in_sheet),
  ) {
    CheckInSheetBody(uiState, actions, canClose)
  }
}

/**
 * Under the handle: the title and ✕, every symptom, how strong each picked one is, when, a note,
 * then Save. It scrolls, and stays above the keyboard, with a large font or a phone held sideways.
 */
@Composable
private fun CheckInSheetBody(
    uiState: AddSymptomUiState,
    actions: CheckInActions,
    canClose: Boolean,
) {
  Column(
      // The sheet's window keeps it above the keyboard: no padding of its own for it
      Modifier.verticalScroll(rememberScrollState())
          .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
      verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    SheetTitle(canClose, actions.onClose)
    SymptomTiles(
        tiles = sheetTiles,
        onTap = { type -> type?.let(actions.onToggleType) },
        picked = uiState.types,
        tagOf = { C.Tag.overviewCheckInSheet(it?.name.orEmpty()) },
    )
    // As soon as "Other" is picked: it can't be saved without a name
    if (uiState.isOtherPicked) OtherNameField(uiState, actions.onOtherNameChange)
    SymptomStrengths(
        types = uiState.types,
        strengths = uiState.strengths,
        strengthOf = uiState.strengthOf,
        otherName = uiState.savedOtherName,
        onSelect = actions.onSelectStrengthOf,
        onStrengthChange = actions.onStrengthChange,
        onClear = actions.onClearStrength,
    )
    WhenRow(uiState, actions.onDateChange, actions.onTimeChange)
    NoteField(uiState, actions.onNotesChange)
    uiState.error?.let {
      Text(
          stringResource(it.messageRes()),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag(C.Tag.overview_check_in_sheet_error),
      )
    }
    // As wide as the fields above it (Figma), not inset like the form's Save
    LakeButton(
        text = stringResource(R.string.overview_check_in_sheet_save),
        onClick = actions.onSave,
        enabled = uiState.canSave,
        isWorking = uiState.save == SavePhase.SAVING,
        workingTestTag = C.Tag.overview_check_in_sheet_saving,
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_check_in_sheet_save),
    )
  }
}

/** "What do you feel?" and ✕, which closes the sheet without saving. */
@Composable
private fun SheetTitle(canClose: Boolean, onClose: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
        stringResource(R.string.overview_check_in_sheet_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f).semantics { heading() },
    )
    IconButton(
        onClick = onClose,
        enabled = canClose,
        modifier = Modifier.testTag(C.Tag.overview_check_in_sheet_close),
    ) {
      Icon(
          painterResource(R.drawable.ic_close),
          stringResource(R.string.overview_check_in_sheet_close),
      )
    }
  }
}

/**
 * "Now, 09:30 · Change", or the day and time the patient chose; tapping it picks a day, then a
 * time. Says so when the time chosen is later than now, which can't be saved.
 */
@Composable
private fun WhenRow(
    uiState: AddSymptomUiState,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
  var picker by rememberSaveable { mutableStateOf(WhenPicker.NONE) }
  val today = uiState.now.toLocalDate()
  val time = (uiState.time ?: uiState.now.toLocalTime()).format(symptomTimeFormatter)
  val text =
      if (uiState.isNow) stringResource(R.string.overview_check_in_sheet_now, time)
      else
          stringResource(
              R.string.overview_check_in_sheet_when,
              dayLabel(uiState.date ?: today, today),
              time,
          )
  val color =
      if (uiState.isInFuture) MaterialTheme.colorScheme.error
      else MaterialTheme.colorScheme.onSurfaceVariant
  Column {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = stringResource(R.string.symptom_add_change_date_time),
                role = Role.Button,
            ) {
              picker = WhenPicker.DATE
            }
            .testTag(C.Tag.overview_check_in_sheet_when),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      Icon(
          painterResource(R.drawable.ic_schedule),
          contentDescription = null,
          tint = color,
          modifier = Modifier.size(18.dp),
      )
      Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
    if (uiState.isInFuture) {
      Text(
          stringResource(R.string.symptom_add_in_future),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
      )
    }
  }
  WhenPickers(picker, { picker = it }, uiState, onDateChange, onTimeChange)
}

/**
 * "Add a note (optional)" (Figma: "Note"). No microphone: speaking a symptom starts from the
 * Overview's "Speak how you feel", not from inside the sheet.
 */
@Composable
private fun NoteField(uiState: AddSymptomUiState, onNotesChange: (String) -> Unit) {
  LakeTextField(
      value = uiState.notes,
      onValueChange = onNotesChange,
      placeholder = { Text(stringResource(R.string.overview_check_in_sheet_note)) },
      maxLines = 4,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.overview_check_in_sheet_note),
  )
}
