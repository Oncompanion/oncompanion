// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.reminder

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentReminder
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.reminder.*
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Reconciliation outcome; activation will decide where to expose/retry blocked or failed work. */
enum class AppointmentReminderSync {
  IDLE,
  SYNCHRONIZED,
  BLOCKED,
  FAILED,
}

/**
 * Reconciles the signed-in patient's appointment snapshots with device alarms. Nothing starts until
 * [observe] is collected by the Activity-owned integration. Receipts suppress repeated immediate
 * scheduling across normal process recreation, not a device reboot or an atomic transaction between
 * preferences and AlarmManager. Alarms are not restored after the phone restarts.
 */
class ReconcileAppointmentReminders(
    private val text: AppointmentReminderText,
    private val appointments: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val profiles: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val scheduler: ReminderScheduler = ReminderSchedulerProvider.scheduler,
    private val plans: AppointmentReminderPlans = AppointmentReminderPlansProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId? = null,
) {
  /**
   * Follows this patient's cached appointments, including caregiver edits; failures are not
   * deletion. A failure ends the affected observation: recollect to resume appointment updates and
   * retry blocked work (an account change also starts a new patient observation). Account changes
   * stop the previous observation. Recheck undelivered receipts on resume to repair alarms removed
   * by Android; consumed deliveries never replay solely because settings or the app were reopened.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(repairUndelivered: Boolean = false): Flow<AppointmentReminderSync> =
      auth
          .observeCurrentUser()
          .flatMapLatest { user ->
            if (user == null) flowOf(AppointmentReminderSync.IDLE)
            else observePatient(user.uid, repairUndelivered)
          }
          .failuresAsState()

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun observePatient(
      owner: String,
      repairUndelivered: Boolean,
  ): Flow<AppointmentReminderSync> =
      profiles
          .observeProfile(owner)
          .flatMapLatest { profile ->
            if (profile?.role != Role.PATIENT) flowOf(AppointmentReminderSync.IDLE)
            else {
              var first = repairUndelivered
              appointments.observeAppointments(owner).map {
                reconcile(owner, it, first).also { first = false }
              }
            }
          }
          .failuresAsState()

  // Keep failures local to their observation, while cancellation must still stop the old account.
  private fun Flow<AppointmentReminderSync>.failuresAsState(): Flow<AppointmentReminderSync> =
      catch { error ->
        if (error is CancellationException) throw error
        emit(AppointmentReminderSync.FAILED)
      }

  private fun reconcile(
      owner: String,
      entries: List<Appointment>,
      repairUndelivered: Boolean,
  ): AppointmentReminderSync = SessionAccess.immediate {
    // A late snapshot cannot schedule for an account whose auth callback has not arrived yet.
    if (auth.currentUser?.uid != owner) return@immediate AppointmentReminderSync.IDLE
    val now = clock.instant()
    val saved = plans.read(owner).toMutableMap()
    val eligible = eligibleAppointments(entries, now)
    for (id in saved.keys.toList() - eligible.keys) {
      scheduler.cancel(key(owner, id))
      saved.remove(id)
      plans.write(owner, saved)
    }
    var blocked = false
    for ((id, candidate) in eligible) {
      val appointment = candidate.appointment
      val due = candidate.due
      val timing = hash(listOf(appointment.scheduledAt.toString(), appointment.reminder.name))
      val content = text.format(appointment, zoneId ?: ZoneId.systemDefault())
      val revision = hash(listOf(content.title, content.text))
      val next = AppointmentReminderPlan(due, timing, revision)
      if (!needsUpdate(saved[id], next, now, repairUndelivered, key(owner, id))) continue
      val result =
          scheduler.schedule(Reminder(key(owner, id), maxOf(due, now), content.title, content.text))
      if (result == ReminderScheduleResult.SCHEDULED) saved[id] = next
      else {
        saved.remove(id)
        blocked = true
      }
      // Only an accepted plan suppresses a retry; blocked permissions never count as delivery.
      plans.write(owner, saved)
    }
    if (blocked) AppointmentReminderSync.BLOCKED else AppointmentReminderSync.SYNCHRONIZED
  }

  private data class Candidate(val appointment: Appointment, val due: Instant)

  private fun eligibleAppointments(
      entries: List<Appointment>,
      now: Instant,
  ): Map<String, Candidate> =
      entries
          .mapNotNull { appointment ->
            val offset = offset(appointment.reminder) ?: return@mapNotNull null
            if (
                appointment.id.isBlank() || !appointment.isValid() || appointment.scheduledAt <= now
            )
                null
            else appointment.id to Candidate(appointment, appointment.scheduledAt.minus(offset))
          }
          .toMap()

  // Once due, unrelated edits must not replay the immediate reminder. Timing changes may.
  private fun needsUpdate(
      previous: AppointmentReminderPlan?,
      next: AppointmentReminderPlan,
      now: Instant,
      repair: Boolean,
      key: ReminderKey,
  ): Boolean {
    if (previous == null || previous.timing != next.timing) return true
    // A receipt is not proof Android retained an alarm after exact-alarm access was revoked.
    if (repair && !scheduler.wasDelivered(key)) return true
    return previous.content != next.content && previous.due > now
  }

  private fun key(owner: String, id: String) = ReminderKey(owner, "appointment", id)

  private fun hash(parts: List<String>): String =
      MessageDigest.getInstance("SHA-256")
          .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

  private fun offset(choice: AppointmentReminder): Duration? =
      when (choice) {
        AppointmentReminder.NONE -> null
        AppointmentReminder.FIFTEEN_MINUTES -> Duration.ofMinutes(15)
        AppointmentReminder.ONE_HOUR -> Duration.ofHours(1)
        AppointmentReminder.THREE_HOURS -> Duration.ofHours(3)
        AppointmentReminder.ONE_DAY -> Duration.ofDays(1)
      }
}
