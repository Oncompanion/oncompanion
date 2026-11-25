// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.reminder

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.reminder.MedicationReminderPlan
import com.github.se.oncompanion.model.reminder.MedicationReminderPlans
import com.github.se.oncompanion.model.reminder.MedicationReminderPlansProvider
import com.github.se.oncompanion.model.reminder.Reminder
import com.github.se.oncompanion.model.reminder.ReminderKey
import com.github.se.oncompanion.model.reminder.ReminderScheduleResult
import com.github.se.oncompanion.model.reminder.ReminderScheduler
import com.github.se.oncompanion.model.reminder.ReminderSchedulerProvider
import com.github.se.oncompanion.model.session.SessionAccess
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What the last check of the medication reminders did, for the caller to show or retry. */
enum class MedicationReminderSync {
  /** No one is signed in. */
  IDLE,
  /** Every reminder is scheduled. */
  SYNCHRONIZED,
  /** Android blocks notifications or exact alarms: the blocked ones are tried again next time. */
  BLOCKED,
  /** The medications couldn't be read: nothing was cancelled, the caller collects again. */
  FAILED,
}

/**
 * Keeps the phone's alarms in step with the signed-in patient's medication reminders (US-32): one
 * per reminder time, every day from the start date to the end of the duration. Only the next
 * [WINDOW_DAYS] days are scheduled, as each alarm rings once: collecting [observe] again, when the
 * app opens, schedules the following days. A time already passed is never caught up.
 *
 * The reminder says the medication's name, dosage and frequency as recorded: it only helps the
 * patient remember, never says whether to take it. Nothing starts until [observe] is collected.
 */
class ReconcileMedicationReminders(
    private val medications: MedicationRepository = MedicationRepositoryProvider.repository,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val scheduler: ReminderScheduler = ReminderSchedulerProvider.scheduler,
    private val plans: MedicationReminderPlans = MedicationReminderPlansProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId? = null,
) {

  /**
   * Follows the signed-in account's medications and schedules or cancels their reminders after each
   * change. Only confirmed prescriptions count: one a caregiver added reminds of nothing until the
   * patient confirms it (US-43). After [MedicationReminderSync.FAILED] the observation ends, and
   * the caller collects again.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(): Flow<MedicationReminderSync> =
      auth
          .observeCurrentUser()
          .flatMapLatest { user ->
            if (user == null) flowOf(MedicationReminderSync.IDLE)
            else medications.observeMedications(user.uid).map { reconcile(user.uid, it) }.orFailed()
          }
          .orFailed()

  // A failure ends this observation, but cancelling it (the account changed) is not one
  private fun Flow<MedicationReminderSync>.orFailed() = catch { error ->
    if (error is CancellationException) throw error
    emit(MedicationReminderSync.FAILED)
  }

  private fun reconcile(owner: String, entries: List<Medication>): MedicationReminderSync =
      SessionAccess.immediate {
        // A late snapshot can't schedule for an account that signed out in the meantime
        if (auth.currentUser?.uid != owner) return@immediate MedicationReminderSync.IDLE
        val now = clock.instant()
        val zone = zoneId ?: ZoneId.systemDefault()
        val wanted =
            entries.flatMap { upcoming(owner, it, now, zone) }.associateBy { it.key.itemId }
        val saved = plans.read(owner).toMutableMap()
        cancelRemoved(owner, wanted.keys, saved, now)
        val blocked = scheduleWanted(wanted, saved)
        plans.write(owner, saved)
        if (blocked) MedicationReminderSync.BLOCKED else MedicationReminderSync.SYNCHRONIZED
      }

  /**
   * Cancels the [saved] reminders no longer [wanted]; one that already rang keeps its notification.
   */
  private fun cancelRemoved(
      owner: String,
      wanted: Set<String>,
      saved: MutableMap<String, MedicationReminderPlan>,
      now: Instant,
  ) {
    for ((id, plan) in saved.toMap()) {
      if (id in wanted) continue
      if (plan.due > now) scheduler.cancel(key(owner, id))
      saved.remove(id)
    }
  }

  /**
   * Schedules the [wanted] reminders not already in [saved], recording the accepted ones; whether
   * Android blocked any of them.
   */
  private fun scheduleWanted(
      wanted: Map<String, Reminder>,
      saved: MutableMap<String, MedicationReminderPlan>,
  ): Boolean {
    var blocked = false
    for ((id, reminder) in wanted) {
      val plan = MedicationReminderPlan(reminder.at, hash(reminder.title, reminder.text))
      if (saved[id] == plan) continue
      if (scheduler.schedule(reminder) == ReminderScheduleResult.SCHEDULED) saved[id] = plan
      else {
        // Not recorded, so the next collection tries it again
        saved.remove(id)
        blocked = true
      }
    }
    return blocked
  }

  /** The reminders of [medication] still to ring in the next [WINDOW_DAYS] days. */
  private fun upcoming(
      owner: String,
      medication: Medication,
      now: Instant,
      zone: ZoneId,
  ): List<Reminder> {
    val today = now.atZone(zone).toLocalDate()
    val lastDay = medication.durationDays?.let { medication.startDate.plusDays(it - 1L) }
    val text =
        listOfNotNull(medication.dosage, medication.frequency)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    return (0L until WINDOW_DAYS)
        .map { today.plusDays(it) }
        .filter { it >= medication.startDate && (lastDay == null || it <= lastDay) }
        .flatMap { day ->
          medication.reminderTimes.mapNotNull { time ->
            // The patient chose the time on this phone, so it rings at that time where they are
            val at = day.atTime(time).atZone(zone).toInstant()
            val id = "${medication.id}@${day}T$time"
            if (at > now) Reminder(key(owner, id), at, medication.name, text) else null
          }
        }
  }

  private fun key(owner: String, id: String) = ReminderKey(owner, FEATURE, id)

  private fun hash(vararg parts: String): String =
      MessageDigest.getInstance("SHA-256")
          .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it) }

  companion object {
    /** The days scheduled ahead: at most 12 alarms per medication, far below Android's limit. */
    const val WINDOW_DAYS = 3L

    /** The [ReminderKey.feature] of medication reminders. */
    const val FEATURE = "medication"
  }
}
