// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeBanner
import com.github.se.oncompanion.ui.common.LakeBannerTone
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.LakeFilterChip
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeSnackbarHost
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.common.MessageArea
import com.github.se.oncompanion.ui.common.SaveButton
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.common.appSettingsIntent
import com.github.se.oncompanion.ui.common.hasPermission
import com.github.se.oncompanion.ui.common.isCompactHeight
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The add symptom screen (US-07 "Log a symptom", Figma: "US-04 / Log symptoms"): the user picks
 * what they feel and saves it, optionally with how bad, when and notes. With a ViewModel editing a
 * symptom, it is the edit screen instead (US-09, Figma: "US-9 / Edit symptom entry").
 *
 * @param onBack called when the user leaves without saving
 * @param onSaved called once the symptom is saved
 */
@Composable
fun AddSymptomScreen(
    viewModel: AddSymptomViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    caregiverOf: String? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Android's microphone dialog, after the app said why; its answer goes to the view model
  val microphone =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onMicrophoneAnswer(it)
      }
  // Back from the phone's settings with the microphone allowed: listen right away
  LifecycleResumeEffect(uiState.voice) {
    if (
        uiState.voice == VoiceUiState.MicrophoneRefused &&
            context.hasPermission(Manifest.permission.RECORD_AUDIO)
    ) {
      viewModel.onMicrophoneAllowedInSettings()
    }
    onPauseOrDispose {}
  }

  // Opened from "Speak how you feel" on the Overview (US-73): starts dictating once shown
  LaunchedEffect(viewModel) {
    viewModel.onOpened(context.hasPermission(Manifest.permission.RECORD_AUDIO))
  }

  // Tells the view model when the phone's download dialog covers the screen, and when it closes
  LifecycleResumeEffect(viewModel) {
    viewModel.onScreenShown()
    onPauseOrDispose { viewModel.onScreenHidden() }
  }

  // Leaves the screen once the symptom is saved
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(uiState.save) { if (uiState.save == SavePhase.SAVED) currentOnSaved() }

  // Keeps the time shown (and the check that the chosen time isn't later) current while the form
  // is on screen: refreshes when it comes back, then each time a new minute starts
  val scope = rememberCoroutineScope()
  LifecycleResumeEffect(viewModel) {
    val ticker = scope.launch {
      while (true) {
        delay(viewModel.refreshNow().toMillis())
      }
    }
    onPauseOrDispose { ticker.cancel() }
  }

  // Dictating replaces the form until the patient is done or goes back to it
  val voice = uiState.voice
  if (voice != null) {
    // Opened from the Overview to speak, closing goes back there rather than to an empty form
    val closeVoice = if (uiState.leavesOnClose) onBack else viewModel::cancelVoice
    BackHandler(onBack = closeVoice)
    VoiceSymptomContent(
        voice = voice,
        today = uiState.now.toLocalDate(),
        onClose = closeVoice,
        onTypeInstead = viewModel::cancelVoice,
        leavesOnClose = uiState.leavesOnClose,
        onAllowMicrophone = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
        onDone = viewModel::onDone,
        onStartOver = viewModel::restartListening,
        onDownloadForOffline = viewModel::onDownloadForOffline,
        onNotNow = viewModel::onNotNow,
        modifier = modifier,
    )
    return
  }

  AddSymptomContent(
      uiState = uiState,
      onBack = onBack,
      onSpeak = { viewModel.onSpeak(context.hasPermission(Manifest.permission.RECORD_AUDIO)) },
      onToggleType = viewModel::toggleType,
      onSelectStrengthOf = viewModel::selectStrengthOf,
      onStrengthChange = viewModel::onStrengthChange,
      onClearStrength = viewModel::clearStrength,
      onDateChange = viewModel::onDateChange,
      onTimeChange = viewModel::onTimeChange,
      onNotesChange = viewModel::onNotesChange,
      onOtherNameChange = viewModel::onOtherNameChange,
      onSave = viewModel::save,
      onErrorShown = viewModel::clearError,
      onRetry = viewModel::load,
      modifier = modifier,
      caregiverOf = caregiverOf,
  )
}

/**
 * Stateless content of the add symptom screen, so each state can be tested without a ViewModel.
 *
 * @param onErrorShown called once the error snackbar is gone
 * @param onSpeak dictates the symptom instead (US-44), offered when the phone can listen
 * @param onRetry loads the symptom to edit again after an error
 * @param caregiverOf in caregiver mode, the first name of the patient whose journal gets the
 *   symptom, said in the title so the caregiver knows where it goes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSymptomContent(
    uiState: AddSymptomUiState,
    onBack: () -> Unit,
    onToggleType: (SymptomType) -> Unit,
    onSelectStrengthOf: (SymptomType) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onClearStrength: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    onSpeak: () -> Unit = {},
    onRetry: () -> Unit = {},
    caregiverOf: String? = null,
    onOtherNameChange: (String) -> Unit = {},
) {
  // Not while saving or once saved: the screen then leaves by itself, and going back as well would
  // also leave the journal
  BackHandler(enabled = uiState.save != SavePhase.EDITING) {}

  // Shows each saving error once in a snackbar, then tells the view model it was shown
  val snackbarHostState = remember { SnackbarHostState() }
  val errorMessage = uiState.error?.let { stringResource(it.messageRes()) }
  val currentOnErrorShown by rememberUpdatedState(onErrorShown)
  LaunchedEffect(uiState.error) {
    if (errorMessage != null) {
      snackbarHostState.showSnackbar(errorMessage)
      currentOnErrorShown()
    }
  }

  // A short screen (a phone held sideways) keeps nothing fixed: with the keyboard open, a fixed top
  // bar and Save would leave no room for the notes being typed
  val scrollsEverything = isCompactHeight()

  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Save sits just above it
      modifier = modifier.testTag(C.Tag.symptom_add_screen).imePadding(),
      // Without the form there is no keyboard, so the top bar can stay fixed
      topBar = {
        if (!scrollsEverything || !uiState.isFormShown) FormTopBar(uiState, caregiverOf, onBack)
      },
      snackbarHost = { LakeSnackbarHost(snackbarHostState) },
      // Save stays at the bottom of the screen while the fields scroll
      bottomBar = {
        if (!scrollsEverything && uiState.isFormShown) FormButtons(uiState, onSpeak, onSave)
      },
  ) { innerPadding ->
    // When editing, the symptom is loaded before the form can show it
    when {
      uiState.isLoading ->
          Box(Modifier.fillMaxSize().padding(innerPadding)) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_edit_loading)
            )
          }
      uiState.hasLoadError ->
          MessageArea(Modifier.padding(innerPadding)) {
            LakeMessageState(
                title = stringResource(R.string.symptom_edit_error_title),
                message = stringResource(R.string.symptom_edit_error_hint),
                actionLabel = stringResource(R.string.symptom_retry),
                onAction = onRetry,
                actionTestTag = C.Tag.symptom_edit_retry,
                modifier = Modifier.testTag(C.Tag.symptom_edit_error),
            )
          }
      uiState.isNotFound ->
          MessageArea(Modifier.padding(innerPadding)) {
            LakeMessageState(
                title = stringResource(R.string.symptom_detail_not_found_title),
                message = stringResource(R.string.symptom_detail_not_found_hint),
                modifier = Modifier.testTag(C.Tag.symptom_edit_not_found),
            )
          }
      else ->
          SymptomFields(
              uiState = uiState,
              onToggleType = onToggleType,
              onSelectStrengthOf = onSelectStrengthOf,
              onStrengthChange = onStrengthChange,
              onClearStrength = onClearStrength,
              onDateChange = onDateChange,
              onTimeChange = onTimeChange,
              onNotesChange = onNotesChange,
              onOtherNameChange = onOtherNameChange,
              onSpeak = onSpeak,
              scrollsEverything = scrollsEverything,
              caregiverOf = caregiverOf,
              onBack = onBack,
              onSave = onSave,
              modifier = Modifier.padding(innerPadding),
          )
    }
  }
}

/**
 * The title on the Paper lake band, with the button that leaves the form without saving.
 * [caregiverOf] is the patient a caregiver logs the symptom for, `null` for the user's own journal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormTopBar(
    uiState: AddSymptomUiState,
    caregiverOf: String?,
    onBack: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
  LakeTopAppBar(
      title =
          when {
            // A caregiver sees whose journal gets the symptom, or whose entry they edit (US-46)
            uiState.isEditing && caregiverOf != null ->
                stringResource(R.string.symptom_edit_title_for, caregiverOf)
            uiState.isEditing -> stringResource(R.string.symptom_edit_title)
            caregiverOf != null -> stringResource(R.string.symptom_add_title_for, caregiverOf)
            else -> stringResource(R.string.symptom_add_title)
          },
      navigationIcon = {
        // Not while saving or once saved: the screen then leaves by itself, and a second way out
        // would go back twice
        IconButton(
            onClick = onBack,
            enabled = uiState.save == SavePhase.EDITING,
            modifier = Modifier.testTag(C.Tag.symptom_add_close),
        ) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.symptom_add_close))
        }
      },
      windowInsets = windowInsets,
  )
}

/**
 * "Speak instead" when the phone can listen, above the button that saves the symptom, so both are
 * in reach of the thumb (Figma: Voice logging).
 */
@Composable
private fun FormButtons(uiState: AddSymptomUiState, onSpeak: () -> Unit, onSave: () -> Unit) {
  Column {
    if (uiState.canDictate) {
      LakeButton(
          text = stringResource(R.string.symptom_voice_speak_instead),
          onClick = onSpeak,
          style = LakeButtonStyle.Tonal,
          enabled = uiState.save == SavePhase.EDITING,
          icon = painterResource(R.drawable.ic_mic),
          modifier =
              Modifier.fillMaxWidth()
                  .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md)
                  .testTag(C.Tag.symptom_add_speak),
      )
    }
    FormSaveButton(uiState, onSave)
  }
}

/** The button that saves the symptom, worded for logging or for editing. */
@Composable
private fun FormSaveButton(uiState: AddSymptomUiState, onSave: () -> Unit) {
  SaveButton(
      text =
          stringResource(
              if (uiState.isEditing) R.string.symptom_edit_save else R.string.symptom_add_save
          ),
      enabled = uiState.canSave,
      isSaving = uiState.save == SavePhase.SAVING,
      onClick = onSave,
      testTag = C.Tag.symptom_add_save,
      savingTestTag = C.Tag.symptom_add_saving,
  )
}

/**
 * The fields of the form, which scroll between the top bar and Save, or with them when
 * [scrollsEverything] is set.
 */
@Composable
private fun SymptomFields(
    uiState: AddSymptomUiState,
    onToggleType: (SymptomType) -> Unit,
    onSelectStrengthOf: (SymptomType) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onClearStrength: () -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onNotesChange: (String) -> Unit,
    onOtherNameChange: (String) -> Unit,
    onSpeak: () -> Unit,
    scrollsEverything: Boolean,
    caregiverOf: String?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    // The Scaffold already keeps the content clear of the status bar
    if (scrollsEverything) FormTopBar(uiState, caregiverOf, onBack, WindowInsets(0))
    // The form groups, 24 dp apart; each is its question over its control
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
      // Filled from what the patient said: they check it before saving (drafted content)
      if (uiState.isDrafted) {
        LakeBanner(
            message = stringResource(R.string.symptom_voice_drafted),
            tone = LakeBannerTone.Waiting,
            modifier = Modifier.testTag(C.Tag.symptom_add_drafted),
        )
      }
      FormGroup(R.string.symptom_add_types) {
        TypeChips(uiState.types, onToggleType)
        // Under the chips, as soon as "Other" is picked: it can't be saved without a name
        if (uiState.isOtherPicked) OtherNameField(uiState, onOtherNameChange)
      }
      // How strong each picked symptom was, once one is picked
      SymptomStrengths(
          types = uiState.types,
          strengths = uiState.strengths,
          strengthOf = uiState.strengthOf,
          otherName = uiState.savedOtherName,
          onSelect = onSelectStrengthOf,
          onStrengthChange = onStrengthChange,
          onClear = onClearStrength,
          overall = uiState.intensity,
      )
      FormGroup(R.string.symptom_add_when) { WhenField(uiState, onDateChange, onTimeChange) }
      FormGroup(R.string.symptom_add_notes_title) { NotesField(uiState, onNotesChange) }
    }
    if (scrollsEverything) FormButtons(uiState, onSpeak, onSave)
  }
}

/** A question as a heading, with its control right under it. */
@Composable
private fun FormGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    content()
  }
}

/** One chip per symptom type, checked when picked; several can be picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(picked: List<SymptomType>, onToggleType: (SymptomType) -> Unit) {
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    SymptomType.entries.forEach { type ->
      val selected = type in picked
      LakeFilterChip(
          selected = selected,
          onClick = { onToggleType(type) },
          label = stringResource(type.label),
          modifier = Modifier.testTag(C.Tag.symptomAddType(type.name)),
      )
    }
  }
}
