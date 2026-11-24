// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.theme.Spacing

// The width a medication's start date and duration need to sit side by side, at the normal font
// size: a 360 dp phone has 296 dp. Below it, e.g. on a 320 dp phone or at a large font size, the
// longest values ("30 Dec 2026", "365 days") are cut, so they are stacked
private val SIDE_BY_SIDE_MIN_WIDTH = 260.dp

// The start date is about twice as wide as the duration, as in the frame (206 and 110 dp)
private const val START_DATE_WEIGHT = 1.87f

/**
 * The start date and the duration of a medication: on one row, the date wider as in the frame, or
 * one under the other when [stacked] or when the row is too narrow for the font size (a small
 * phone, a large font), so neither value is cut.
 */
@Composable
internal fun StartAndDuration(
    stacked: Boolean,
    startDate: @Composable (Modifier) -> Unit,
    duration: @Composable (Modifier) -> Unit,
) {
  BoxWithConstraints {
    // The room counted at the normal font size: a larger font needs a wider row
    val fits = maxWidth / LocalDensity.current.fontScale >= SIDE_BY_SIDE_MIN_WIDTH
    if (stacked || !fits) {
      // The card's gap between fields
      Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        startDate(Modifier.fillMaxWidth())
        duration(Modifier.fillMaxWidth())
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        startDate(Modifier.weight(START_DATE_WEIGHT))
        duration(Modifier.weight(1f))
      }
    }
  }
}

/**
 * How many days a medication lasts, empty when it has no end. [durationInvalid] shows the red
 * warning that it can't be saved; otherwise [durationCheck] asks to check it. Both come from the
 * caller, which also stacks the row when either is shown.
 */
@Composable
internal fun DurationField(
    medication: MedicationDraft,
    durationInvalid: Boolean,
    durationCheck: FieldCheck?,
    onDurationChange: (String) -> Unit,
    modifier: Modifier,
) {
  LakeTextField(
      value = medication.durationDays?.toString().orEmpty(),
      onValueChange = onDurationChange,
      label = { Text(stringResource(R.string.prescription_form_duration)) },
      // "3 days" as in the frames: the unit is part of the value, not Material's suffix, which
      // takes the label's width once focused and breaks "Duration" in two on this narrow field
      visualTransformation =
          UnitAfterNumber(
              pluralStringResource(
                  R.plurals.prescription_form_duration_unit,
                  medication.durationDays ?: 0,
              ),
              MaterialTheme.colorScheme.onSurfaceVariant,
          ),
      supportingText =
          if (durationInvalid) {
            {
              Text(
                  stringResource(
                      R.string.prescription_form_duration_invalid,
                      Medication.MAX_DURATION_DAYS,
                  )
              )
            }
          } else durationCheck?.let { { CheckReason(it) } },
      trailingIcon = durationCheck?.let { { CheckIcon() } },
      isError = durationInvalid,
      isCheck = durationCheck != null,
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = modifier.testTag(C.Tag.prescription_form_duration_field),
  )
}

/**
 * Shows [unit] after a typed number, in [color], as the frames' "3 days"; nothing after an empty
 * value. Only the display changes: the value stays the number, and the cursor stays among its
 * digits.
 */
private class UnitAfterNumber(private val unit: String, private val color: Color) :
    VisualTransformation {
  override fun filter(text: AnnotatedString): TransformedText {
    if (text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
    val shown = buildAnnotatedString {
      append(text)
      withStyle(SpanStyle(color = color)) { append(" $unit") }
    }
    val typed = text.length
    return TransformedText(
        shown,
        object : OffsetMapping {
          override fun originalToTransformed(offset: Int) = offset

          override fun transformedToOriginal(offset: Int) = offset.coerceAtMost(typed)
        },
    )
  }
}
