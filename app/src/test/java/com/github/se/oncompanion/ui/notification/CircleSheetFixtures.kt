// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.notification

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.notification.CircleNotification
import com.github.se.oncompanion.model.notification.FakeNotificationRepository
import com.github.se.oncompanion.model.notification.NotificationKind
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/** The patient whose circle [circleSheetViewModel] reads. */
internal const val CIRCLE_PATIENT = "alex"

/**
 * A "Your circle" ViewModel on fakes, for [CIRCLE_PATIENT] signed in: [members] in the circle, and
 * each prescription in [added] added by Marc a minute before [now], not seen yet. Read on a [clock]
 * at [now] unless given.
 */
internal fun circleSheetViewModel(
    now: Instant,
    clock: Clock = Clock.fixed(now, ZoneId.systemDefault()),
    members: List<CareCircleMember> = listOf(CareCircleMember("marc", "Marc")),
    added: List<Prescription> = emptyList(),
    medications: FakeMedicationRepository = FakeMedicationRepository(),
): CircleSheetViewModel {
  val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = it) })
  runBlocking { auth.signInWithGoogle(CIRCLE_PATIENT) }
  val circle = FakeCareCircleRepository()
  circle.setMembers(CIRCLE_PATIENT, members)
  val notifications = FakeNotificationRepository { now }
  for (prescription in added) {
    medications.seed(CIRCLE_PATIENT, prescription)
    notifications.deliver(
        CIRCLE_PATIENT,
        CircleNotification(
            kind = NotificationKind.PRESCRIPTION_ADDED,
            itemId = prescription.id,
            actorUid = "marc",
            actorFirstName = "Marc",
            createdAt = now.minusSeconds(60),
        ),
    )
  }
  return CircleSheetViewModel(
      notifications,
      circle,
      FakeCareCircleInviteRepository(),
      medications,
      auth,
      clock,
  )
}
