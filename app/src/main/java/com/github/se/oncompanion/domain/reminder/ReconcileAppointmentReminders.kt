// Co-authored-by: ChatGPT <noreply@openai.com>

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
 * [observe] is collected; this slice deliberately installs no application/lifecycle collector.
 * Receipts suppress repeated immediate scheduling across normal process recreation, not a device
 * reboot or an atomic transaction between preferences and AlarmManager (recovery remains #454).
 */
class ReconcileAppointmentReminders(
    private val text: AppointmentReminderText,
    private val appointments: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val profiles: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val scheduler: ReminderScheduler = ReminderSchedulerProvider.scheduler,
    private val plans: AppointmentReminderPlans = AppointmentReminderPlansProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
  /**
   * Follows this patient's cached appointments, including caregiver edits; failures are not
   * deletion. Recollection retries blocked/failed work. Account changes stop the previous
   * observation; alarm cancellation/restoration, boot/permission wiring and tap navigation follow
   * separately.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(): Flow<AppointmentReminderSync> = channelFlow {
    auth.observeCurrentUser().collectLatest { user ->
      flow {
        if (user == null) emit(AppointmentReminderSync.IDLE)
        else
            emitAll(
                profiles.observeProfile(user.uid).flatMapLatest { profile ->
                  if (profile?.role != Role.PATIENT) flowOf(AppointmentReminderSync.IDLE)
                  else appointments.observeAppointments(user.uid).map { reconcile(user.uid, it) }
                }
            )
      }
          .catch { error ->
            if (error is CancellationException) throw error
            emit(AppointmentReminderSync.FAILED)
          }
          .collect { send(it) }
    }
  }
      .catch { error ->
        if (error is CancellationException) throw error
        emit(AppointmentReminderSync.FAILED)
      }

  private fun reconcile(owner: String, entries: List<Appointment>): AppointmentReminderSync =
      SessionAccess.immediate {
        // A late snapshot cannot schedule for an account whose auth callback has not arrived yet.
        if (auth.currentUser?.uid != owner) return@immediate AppointmentReminderSync.IDLE
        val now = clock.instant()
        val saved = plans.read(owner).toMutableMap()
        val eligible =
            entries
                .filter {
                  it.id.isNotBlank() &&
                      it.isValid() &&
                      it.reminder != AppointmentReminder.NONE &&
                      it.scheduledAt > now
                }
                .associateBy { it.id }
        for (id in saved.keys.toList() - eligible.keys) {
          scheduler.cancel(key(owner, id))
          saved.remove(id)
          plans.write(owner, saved)
        }
        var blocked = false
        for ((id, appointment) in eligible) {
          val due = appointment.scheduledAt.minus(offset(appointment.reminder))
          val timing = hash(listOf(appointment.scheduledAt.toString(), appointment.reminder.name))
          val content = text.format(appointment, zoneId)
          val revision = hash(listOf(content.title, content.text))
          val previous = saved[id]
          // Once due, unrelated edits must not replay the immediate reminder. Timing changes may.
          if (previous?.timing == timing && (previous.content == revision || previous.due <= now))
              continue
          val result =
              scheduler.schedule(
                  Reminder(key(owner, id), maxOf(due, now), content.title, content.text)
              )
          if (result == ReminderScheduleResult.SCHEDULED)
              saved[id] = AppointmentReminderPlan(due, timing, revision)
          else {
            saved.remove(id)
            blocked = true
          }
          // Only an accepted plan suppresses a retry; blocked permissions never count as delivery.
          plans.write(owner, saved)
        }
        if (blocked) AppointmentReminderSync.BLOCKED else AppointmentReminderSync.SYNCHRONIZED
      }

  private fun key(owner: String, id: String) = ReminderKey(owner, "appointment", id)

  private fun hash(parts: List<String>): String =
      MessageDigest.getInstance("SHA-256")
          .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

  private fun offset(choice: AppointmentReminder): Duration =
      when (choice) {
        AppointmentReminder.NONE -> Duration.ZERO
        AppointmentReminder.FIFTEEN_MINUTES -> Duration.ofMinutes(15)
        AppointmentReminder.ONE_HOUR -> Duration.ofHours(1)
        AppointmentReminder.THREE_HOURS -> Duration.ofHours(3)
        AppointmentReminder.ONE_DAY -> Duration.ofDays(1)
      }
}
