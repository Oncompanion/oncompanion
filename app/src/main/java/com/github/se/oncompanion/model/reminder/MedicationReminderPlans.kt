// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.github.se.oncompanion.model.session.SessionAccess
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A medication reminder the scheduler accepted (US-32): when it rings, and a hash of its text, so
 * the phone never keeps a second copy of the medication's name or dosage.
 */
data class MedicationReminderPlan(val due: Instant, val content: String)

/**
 * The medication reminders scheduled on this phone, per account: a removed one can then be
 * cancelled, and an unchanged one isn't scheduled again.
 */
interface MedicationReminderPlans {
  /** This account's reminders by ID; unreadable ones are forgotten, so they're scheduled again. */
  fun read(ownerUid: String): Map<String, MedicationReminderPlan>

  /** Replaces this account's reminders; none removes the account's entry. */
  fun write(ownerUid: String, plans: Map<String, MedicationReminderPlan>)
}

/** [MedicationReminderPlans] in the app's preferences, closed with the session at sign-out. */
class MedicationReminderPlansPreferences(context: Context) : MedicationReminderPlans {
  private val preferences =
      context.applicationContext.getSharedPreferences(
          "medication_reminder_plans",
          Context.MODE_PRIVATE,
      )

  override fun read(ownerUid: String): Map<String, MedicationReminderPlan> =
      SessionAccess.immediate {
        val saved = preferences.getString(ownerUid, null) ?: return@immediate emptyMap()
        try {
          decode(saved)
        } catch (_: JSONException) {
          forget(ownerUid)
        } catch (_: DateTimeParseException) {
          forget(ownerUid)
        }
      }

  // Each reminder is stored as [due, content hash]
  private fun decode(saved: String): Map<String, MedicationReminderPlan> {
    val data = JSONObject(saved)
    return data.keys().asSequence().associateWith { id ->
      val plan = data.getJSONArray(id)
      MedicationReminderPlan(Instant.parse(plan.getString(0)), plan.getString(1))
    }
  }

  // Scheduling again replaces an alarm with the same key, so forgetting them only costs a write
  private fun forget(ownerUid: String): Map<String, MedicationReminderPlan> {
    write(ownerUid, emptyMap())
    return emptyMap()
  }

  override fun write(ownerUid: String, plans: Map<String, MedicationReminderPlan>) =
      SessionAccess.immediate {
        preferences.edit {
          if (plans.isEmpty()) remove(ownerUid)
          else {
            val data = JSONObject()
            plans.forEach { (id, plan) ->
              data.put(id, JSONArray().put(plan.due.toString()).put(plan.content))
            }
            putString(ownerUid, data.toString())
          }
          // KTX returns Unit: check the commit before its final no-op apply
          check(commit()) { "Could not save the medication reminders" }
        }
      }
}

/** Gives the use case its [MedicationReminderPlans]; the app installs it with its context. */
object MedicationReminderPlansProvider {
  @Volatile private var current: MedicationReminderPlans? = null

  /** The installed store, or the shared fake in tests. */
  var repository: MedicationReminderPlans
    get() = checkNotNull(current) { "MedicationReminderPlansProvider.install() wasn't called" }
    set(value) {
      current = value
    }

  /** Installs the preferences store once, keeping a fake a test already set. */
  @Synchronized
  fun install(context: Context) {
    if (current == null) current = MedicationReminderPlansPreferences(context)
  }

  /** Puts the provider back as before [install], between tests. */
  @VisibleForTesting
  internal fun reset() {
    current = null
  }
}
