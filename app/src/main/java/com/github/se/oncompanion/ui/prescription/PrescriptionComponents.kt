// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.ui.common.LakeMessageState
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the prescription screens write a date: "29 Sep 2026". One formatter, so the list, the detail,
 * the form and the notices always agree. The app's UI is in English.
 */
internal val prescriptionDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Where reading the prescriptions of the list or the detail is at, as one value, so two states can
 * never be true at once. What was read is kept apart: an empty list, or a prescription that no
 * longer exists, is still [SHOWN].
 */
enum class PrescriptionsLoad {
  /** The access or the prescriptions are still being read. */
  LOADING,
  /** Read: the list, maybe empty, or the prescription, null if it no longer exists. */
  SHOWN,
  /**
   * Reading failed, and retrying may help. Not a connection problem: offline, the cache answers.
   */
  FAILED,
  /** In caregiver mode, the patient doesn't share their prescriptions. */
  NOT_SHARED,
  /** Nobody is signed in, patient or caregiver: retrying can't help, signing in again does. */
  NOT_SIGNED_IN,
  /**
   * Signed in, but not in the circle of the patient whose prescriptions these are: a caregiver the
   * patient removed. Retrying wouldn't help.
   */
  NO_ACCESS,
}

/**
 * Why nothing can be read when the access check finds no access: nobody is signed in, or the user
 * is signed in but not in the patient's circle. Asked of the sign-in itself, so a caregiver who
 * signs out is never told they were removed (#490).
 */
internal fun noAccessLoad(auth: AuthRepository): PrescriptionsLoad =
    if (runCatching { auth.currentUser }.getOrNull() == null) PrescriptionsLoad.NOT_SIGNED_IN
    else PrescriptionsLoad.NO_ACCESS

/** What the list and the detail say at [PrescriptionsLoad.NOT_SIGNED_IN], to anyone. */
@Composable
internal fun SignedOutMessage() {
  LakeMessageState(
      title = stringResource(R.string.prescriptions_signed_out_title),
      message = stringResource(R.string.prescriptions_signed_out_hint),
  )
}

/**
 * What the list and the detail say at [PrescriptionsLoad.NO_ACCESS]: the caregiver is no longer in
 * the patient's circle. No Retry, since retrying can't change it.
 *
 * @param caregiverOf the patient's first name, which is already gone once a removed caregiver has
 *   left: the text then names nobody, but still says why
 */
@Composable
internal fun NoAccessMessage(caregiverOf: String?) {
  LakeMessageState(
      title =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_title_for, it) }
              ?: stringResource(R.string.prescriptions_no_access_title_unnamed),
      message =
          caregiverOf?.let { stringResource(R.string.prescriptions_no_access_hint_for, it) }
              ?: stringResource(R.string.prescriptions_no_access_hint_unnamed),
  )
}
