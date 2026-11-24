// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.severity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.DeleteConfirmationDialog
import com.github.se.oncompanion.ui.common.DeleteDialogTags
import com.github.se.oncompanion.ui.common.DeletePhase
import com.github.se.oncompanion.ui.common.DetailActionTags
import com.github.se.oncompanion.ui.common.DetailActions
import com.github.se.oncompanion.ui.common.DetailWithActions
import com.github.se.oncompanion.ui.common.EditedBy
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.common.MessageArea
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val actionTags =
    DetailActionTags(edit = C.Tag.symptom_detail_edit, delete = C.Tag.symptom_detail_delete)

private val deleteDialogTags =
    DeleteDialogTags(
        dialog = C.Tag.symptom_delete_dialog,
        confirm = C.Tag.symptom_delete_confirm,
        cancel = C.Tag.symptom_delete_cancel,
        error = C.Tag.symptom_delete_error,
    )

/** "Monday 5 October 2026, 14:30". The app's UI is in English. */
private val detailDateFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

/**
 * The detail of one symptom of the journal (US-8), from which the user can edit or delete it
 * (US-9). Goes back to the journal once it is deleted.
 */
@Composable
fun SymptomDetailScreen(
    navigationActions: NavigationActions,
    viewModel: SymptomDetailViewModel,
    modifier: Modifier = Modifier,
    caregiverOf: String? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Back to the journal, which no longer shows the symptom, once it is deleted
  val goBack by rememberUpdatedState(navigationActions::goBack)
  LaunchedEffect(uiState.delete) { if (uiState.delete == DeletePhase.DELETED) goBack() }

  SymptomDetailContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onRetry = viewModel::load,
      onEdit = { uiState.entry?.let { navigationActions.navigateTo(Screen.symptomEdit(it.id)) } },
      onDelete = viewModel::requestDelete,
      onConfirmDelete = viewModel::confirmDelete,
      onCancelDelete = viewModel::cancelDelete,
      modifier = modifier,
      caregiverOf = caregiverOf,
  )
}

/**
 * Stateless content of the detail, so each state can be tested without a ViewModel.
 *
 * @param onRetry reloads the symptom after an error
 * @param onEdit opens the symptom in the edit form
 * @param onDelete asks to confirm the deletion
 * @param onConfirmDelete deletes the symptom, from the confirmation dialog
 * @param onCancelDelete closes the confirmation dialog and keeps the symptom
 * @param zone the time zone the date is shown in
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun SymptomDetailContent(
    uiState: SymptomDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    onEdit: () -> Unit = {},
    caregiverOf: String? = null,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.symptom_detail_screen),
      topBar = {
        LakeTopAppBar(
            title = uiState.entry?.title() ?: stringResource(R.string.symptom_detail_title),
            navigationIcon = { BackButton(onBack) },
        )
      },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      val entry = uiState.entry
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_detail_loading)
            )
        uiState.hasError ->
            MessageArea(Modifier.testTag(C.Tag.symptom_detail_error)) {
              LakeMessageState(
                  title = stringResource(R.string.symptom_detail_error_title),
                  message = stringResource(R.string.symptom_error_hint),
                  actionLabel = stringResource(R.string.symptom_retry),
                  onAction = onRetry,
                  actionTestTag = C.Tag.symptom_detail_retry,
              )
            }
        // The patient a caregiver follows doesn't share their symptoms (anymore)
        uiState.isNotShared && caregiverOf != null ->
            MessageArea { NotSharedMessage(caregiverOf, CarePermission.SYMPTOMS) }
        entry == null ->
            MessageArea(Modifier.testTag(C.Tag.symptom_detail_not_found)) {
              LakeMessageState(
                  title = stringResource(R.string.symptom_detail_not_found_title),
                  message = stringResource(R.string.symptom_detail_not_found_hint),
              )
            }
        // Edit and Delete at the bottom, away from the back button, as in the Figma mockup
        else ->
            DetailWithActions(
                showActions = uiState.canDelete,
                contentTestTag = C.Tag.symptom_detail_content,
                actions = {
                  DetailActions(
                      editLabel = stringResource(R.string.symptom_detail_edit),
                      deleteLabel = stringResource(R.string.symptom_detail_delete),
                      onEdit = onEdit,
                      onDelete = onDelete,
                      tags = actionTags,
                  )
                },
            ) {
              EntryDetail(entry, zone, uiState.addedByName, uiState.editedByName)
            }
      }
    }
  }

  if (uiState.isDeleteDialogShown) {
    // A caregiver deletes the patient's entry: the dialog says whose it is (US-46)
    DeleteConfirmationDialog(
        title =
            caregiverOf?.let { stringResource(R.string.symptom_delete_title_for, it) }
                ?: stringResource(R.string.symptom_delete_title),
        message =
            caregiverOf?.let { stringResource(R.string.symptom_delete_message_for, it) }
                ?: stringResource(R.string.symptom_delete_message),
        error = stringResource(R.string.symptom_delete_error),
        confirmLabel = stringResource(R.string.symptom_delete_confirm),
        cancelLabel = stringResource(R.string.symptom_delete_cancel),
        isDeleting = uiState.delete == DeletePhase.DELETING,
        hasError = uiState.delete == DeletePhase.FAILED,
        onConfirm = onConfirmDelete,
        onCancel = onCancelDelete,
        tags = deleteDialogTags,
    )
  }
}

/**
 * The symptom in the order of the log form: what was felt, how strong and when, then the notes and
 * who logged it. The symptoms are listed in full, since the title shows only the first line.
 */
@Composable
private fun EntryDetail(
    entry: SymptomEntry,
    zone: ZoneId,
    addedByName: String?,
    editedByName: String?,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(start = Spacing.lg, top = Spacing.md, end = Spacing.lg, bottom = Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.lg),
  ) {
    SymptomsSection(entry)
    InfoCard(entry, zone)
    val notes = entry.notes?.takeIf { it.isNotBlank() }
    if (notes != null) {
      Column(
          modifier = Modifier.testTag(C.Tag.symptom_detail_notes),
          verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        Text(
            text = stringResource(R.string.symptom_detail_notes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    // A symptom a caregiver logged for the patient says who did, and who last edited it (US-46),
    // close together
    if (addedByName != null || editedByName != null) {
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        addedByName?.let { AddedBy(it) }
        editedByName?.let { EditedBy(it) }
      }
    }
  }
}

/**
 * Every symptom of the entry on one white card (Figma: "Symptom detail"), each with its strength
 * when it was given one. An entry saved before strengths has names only: its single intensity is on
 * the [InfoCard].
 */
@Composable
private fun SymptomsSection(entry: SymptomEntry) {
  Surface(
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptom_detail_symptoms),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column(
        modifier = Modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
      Text(
          text = stringResource(R.string.symptom_detail_symptoms),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.semantics { heading() },
      )
      entry.types.forEach { type -> StrengthRow(type, entry.nameOf(type), entry.strengths[type]) }
    }
  }
}

/**
 * A symptom's [name], then its [strength] as "5/10 · Moderate" over a bar filled to it; the name
 * alone when it has no strength. The value has its own line so the name gets the card's whole width
 * and a long one wraps between words, even at a large font.
 */
@Composable
private fun StrengthRow(type: SymptomType, name: String, strength: Int?) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(text = name, style = MaterialTheme.typography.titleMedium)
    if (strength != null) {
      val severity = stringResource(Severity.of(strength).label)
      val description = stringResource(R.string.symptom_strength_description, strength, severity)
      Text(
          text = stringResource(R.string.symptom_add_intensity_value, strength, severity),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.semantics { contentDescription = description },
      )
      StrengthBar(type, strength)
    }
  }
}

/**
 * A bar filled to [strength] out of [SymptomEntry.MAX_INTENSITY], in one colour whatever the value
 * so it never judges it. Hidden from TalkBack: the text above says the value.
 */
@Composable
private fun StrengthBar(type: SymptomType, strength: Int) {
  val tag = C.Tag.symptomDetailStrengthBar(type.name)
  Box(
      Modifier.fillMaxWidth()
          .height(BAR_HEIGHT)
          .clip(MaterialTheme.shapes.full)
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .clearAndSetSemantics { testTag = tag }
  ) {
    Box(
        Modifier.fillMaxWidth(strength.toFloat() / SymptomEntry.MAX_INTENSITY)
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.full)
            .background(MaterialTheme.colorScheme.primary)
    )
  }
}

/** If given, how strong it felt, then when: rows of one white card, split by a divider. */
@Composable
private fun InfoCard(entry: SymptomEntry, zone: ZoneId) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
      // The intensity is optional: no row when the user didn't give one
      val intensity = entry.overallIntensity()
      val severity = entry.severity
      if (intensity != null && severity != null) {
        InfoRow(
            value = stringResource(severity.label),
            label = stringResource(R.string.symptom_detail_intensity),
            testTag = C.Tag.symptom_detail_intensity,
        ) {
          IntensityBadge(intensity)
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
      }
      InfoRow(
          value = entry.occurredAt.atZone(zone).format(detailDateFormatter),
          label = stringResource(R.string.symptom_detail_when),
          testTag = C.Tag.symptom_detail_when,
      ) {
        // Decorative: the label says what the row is
        Icon(painterResource(R.drawable.ic_schedule), contentDescription = null)
      }
    }
  }
}

/** A row of the card: [leading], then its [value] above its [label]. */
@Composable
private fun InfoRow(
    value: String,
    label: String,
    testTag: String,
    leading: @Composable () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = Spacing.lg, vertical = Spacing.md)
              .testTag(testTag),
      horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    leading()
    Column {
      Text(text = value, style = MaterialTheme.typography.titleMedium)
      Text(
          text = label,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * "7/10", read out as "Intensity 7 out of 10". Shown as entered, on the same container whatever the
 * value, so its colour never judges it.
 */
@Composable
private fun IntensityBadge(intensity: Int, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.symptom_intensity_description, intensity)
  Surface(
      modifier =
          modifier.heightIn(min = BADGE_HEIGHT).semantics { contentDescription = description },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
          text = stringResource(R.string.symptom_intensity_value, intensity),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(horizontal = Spacing.sm),
      )
    }
  }
}

// The intensity badge of the Figma's symptom detail, and the height of a symptom's strength bar
private val BADGE_HEIGHT = 36.dp
private val BAR_HEIGHT = 6.dp
