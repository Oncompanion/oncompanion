// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.Severity
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonDefaults
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.LakeFilterChip
import com.github.se.oncompanion.ui.theme.Spacing
import kotlin.math.roundToInt

private const val MIN_INTENSITY = SymptomEntry.MIN_INTENSITY
private const val MAX_INTENSITY = SymptomEntry.MAX_INTENSITY

/**
 * How far the Material slider draws its first and last stops from its edges (half its track
 * height), to line the taps up with the stops. Checked against the rendered slider.
 */
private val SliderStopInset = 8.dp

/**
 * The gap around the handle, Material's "active handle leading space" (private in SliderDefaults).
 */
private val THUMB_TRACK_GAP = 6.dp

/**
 * How strong each picked symptom was (Figma: "Edit entry"), shared by the symptom form and the
 * Overview's check-in sheet (#544). With several symptoms picked, a chip per symptom ("Nausea ·
 * 4/10") chooses which one the slider sets; then the question for that symptom ("How strong is your
 * fatigue? (optional)") and the slider. Nothing shows until a symptom is picked.
 *
 * @param types the symptoms picked, in the order they were picked
 * @param strengths the strength given to each of them, if any
 * @param strengthOf the symptom the slider sets, `null` when none is picked
 * @param otherName what "Other" is called, if the user named it
 * @param onSelect lets the slider set the strength of another picked symptom
 * @param onStrengthChange sets the strength of [strengthOf]
 * @param onClear takes the strength off [strengthOf]
 * @param overall the single intensity of an entry logged before strengths, shown as it was
 * @param overallReplaced the strengths set replace [overall]: its line says so, in the same place
 *   so the chips and the slider under it don't move as the user sets the first strength
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SymptomStrengths(
    types: List<SymptomType>,
    strengths: Map<SymptomType, Int>,
    strengthOf: SymptomType?,
    otherName: String?,
    onSelect: (SymptomType) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    overall: Int? = null,
    overallReplaced: Boolean = false,
) {
  if (strengthOf == null) return
  Column(
      modifier = modifier.testTag(C.Tag.symptom_add_strengths),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    if (overall != null) OverallFromBefore(overall, overallReplaced)
    // One symptom needs no choice: the question names it
    if (types.size > 1) {
      FlowRow(
          horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
          verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        types.forEach { type ->
          StrengthChip(type, strengths[type], type == strengthOf, otherName, onSelect)
        }
      }
    }
    Text(
        text = strengthQuestion(strengthOf, otherName),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    StrengthSlider(strengths[strengthOf], onStrengthChange, onClear)
  }
}

/**
 * "Overall: 6/10 (from before)", then "… · replaced by the strengths" once a strength is set. Both
 * are laid out, the other one unseen and unread, so the line keeps its height either way.
 */
@Composable
private fun OverallFromBefore(overall: Int, replaced: Boolean) {
  val kept = stringResource(R.string.symptom_add_overall_from_before, overall)
  val dropped = stringResource(R.string.symptom_add_overall_replaced, overall)
  Box {
    Text(
        text = if (replaced) kept else dropped,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.alpha(0f).clearAndSetSemantics {},
    )
    Text(
        text = if (replaced) dropped else kept,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(C.Tag.symptom_add_overall),
    )
  }
}

/**
 * A picked symptom, with its strength once it has one ("Nausea · 4/10"), filled when the slider
 * sets it. Read out as "Nausea, intensity 4 out of 10", one of several to choose from.
 */
@Composable
private fun StrengthChip(
    type: SymptomType,
    strength: Int?,
    selected: Boolean,
    otherName: String?,
    onSelect: (SymptomType) -> Unit,
) {
  val name = symptomName(type, otherName)
  val description =
      strength?.let {
        pluralStringResource(R.plurals.symptom_strength_chip_description, it, name, it)
      } ?: name
  LakeFilterChip(
      selected = selected,
      onClick = { onSelect(type) },
      label = strength?.let { stringResource(R.string.symptom_strength_chip, name, it) } ?: name,
      role = Role.RadioButton,
      // As wide as with the strongest strength, so setting one doesn't move the chips
      widestLabel = stringResource(R.string.symptom_strength_chip, name, MAX_INTENSITY),
      modifier =
          Modifier.semantics { contentDescription = description }
              .testTag(C.Tag.symptomAddStrengthChip(type.name)),
  )
}

/**
 * The optional strength of one symptom: "Not set" until the user moves the slider, then the value
 * and its severity in words with Clear to go back to "Not set". It only restates what the user
 * picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrengthSlider(
    strength: Int?,
    onStrengthChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
  val valueText =
      if (strength == null) stringResource(R.string.symptom_add_intensity_not_set)
      else
          stringResource(
              R.string.symptom_add_intensity_value,
              strength,
              stringResource(Severity.of(strength).label),
          )

  // The value in words, and Clear once there is one: as tall as Clear from the start, so the slider
  // doesn't move down when the first value is set
  Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = LakeButtonDefaults.MinHeight),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // The longest value is laid out unseen, so a value on more lines than "Not set" (large text)
    // doesn't push the slider down
    Box(Modifier.weight(1f)) {
      Text(
          text = longestValue(),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.alpha(0f).clearAndSetSemantics {},
      )
      Text(
          text = valueText,
          style = MaterialTheme.typography.titleLarge,
          color =
              if (strength == null) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag(C.Tag.symptom_add_intensity_value),
      )
    }
    // Clear takes its room before there is a value too, unseen and unread
    LakeButton(
        text = stringResource(R.string.symptom_add_intensity_clear),
        onClick = onClear,
        style = LakeButtonStyle.Text,
        enabled = strength != null,
        modifier =
            if (strength != null) Modifier.testTag(C.Tag.symptom_add_intensity_clear)
            else Modifier.alpha(0f).clearAndSetSemantics {},
    )
  }

  // The track is light teal where it isn't picked; "Not set" shows all of it so, without a handle
  // or the gap around it
  val inactive = MaterialTheme.colorScheme.primaryContainer
  val colors =
      if (strength == null)
          SliderDefaults.colors(
              thumbColor = Color.Transparent,
              activeTrackColor = inactive,
              inactiveTrackColor = inactive,
          )
      else SliderDefaults.colors(inactiveTrackColor = inactive)
  // While "Not set" the slider sits on 1 and ignores a touch there, as nothing changes for it: a
  // touch closest to 1 sets it here. The touch isn't taken away, so the slider still follows a
  // drag, and it handles every other touch itself
  val currentStrength by rememberUpdatedState(strength)
  val currentOnStrengthChange by rememberUpdatedState(onStrengthChange)
  Box(
      Modifier.pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
              val touched = strengthAt(down.position.x, size.width, SliderStopInset.toPx())
              if (currentStrength == null && touched == MIN_INTENSITY) {
                currentOnStrengthChange(MIN_INTENSITY)
              }
            }
          }
          .testTag(C.Tag.symptom_add_intensity_track)
  ) {
    Slider(
        value = (strength ?: MIN_INTENSITY).toFloat(),
        onValueChange = { onStrengthChange(it.roundToInt()) },
        valueRange = MIN_INTENSITY.toFloat()..MAX_INTENSITY.toFloat(),
        steps = MAX_INTENSITY - MIN_INTENSITY - 1,
        colors = colors,
        // No dots on the track: the value above says what is picked
        track = { state ->
          SliderDefaults.Track(
              sliderState = state,
              colors = colors,
              drawStopIndicator = null,
              drawTick = { _, _ -> },
              thumbTrackGapSize = if (strength == null) 0.dp else THUMB_TRACK_GAP,
          )
        },
        modifier =
            Modifier.fillMaxWidth()
                .semantics {
                  stateDescription = valueText
                  // Screen readers can set 1 too, which the slider would ignore while "Not set"
                  if (strength == null) {
                    setProgress { target ->
                      onStrengthChange(target.roundToInt().coerceIn(MIN_INTENSITY, MAX_INTENSITY))
                      true
                    }
                  }
                }
                .testTag(C.Tag.symptom_add_intensity),
    )
  }

  // The words at the start, middle and end of the track, each in its own third so they never
  // overlap (a word too long for it wraps); the slider already says the value
  Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
    ScaleLabel(Severity.MILD, Alignment.Start, TextAlign.Start)
    ScaleLabel(Severity.MODERATE, Alignment.CenterHorizontally, TextAlign.Center)
    ScaleLabel(Severity.SEVERE, Alignment.End, TextAlign.End)
  }
}

/** The longest value the slider can say, e.g. "10/10 · Moderate", to keep its room. */
@Composable
private fun longestValue(): String =
    Severity.entries
        .map {
          stringResource(
              R.string.symptom_add_intensity_value,
              MAX_INTENSITY,
              stringResource(it.label),
          )
        }
        .maxBy { it.length }

/** A word of the scale under the slider, put at [alignment] in its third of the width. */
@Composable
private fun RowScope.ScaleLabel(
    severity: Severity,
    alignment: Alignment.Horizontal,
    textAlign: TextAlign,
) {
  Text(
      text = stringResource(severity.label),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = textAlign,
      modifier = Modifier.weight(1f).wrapContentWidth(alignment),
  )
}

/** The strength of the stop closest to [x] on a slider [width] wide. */
private fun strengthAt(x: Float, width: Int, inset: Float): Int {
  val fraction = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
  return (MIN_INTENSITY + fraction * (MAX_INTENSITY - MIN_INTENSITY)).roundToInt()
}
