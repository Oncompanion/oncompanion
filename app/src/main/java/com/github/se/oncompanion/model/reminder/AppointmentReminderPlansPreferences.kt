// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.github.se.oncompanion.model.session.SessionAccess
import java.time.Instant
import java.time.format.DateTimeParseException
import org.json.JSONException
import org.json.JSONObject

/** Local scheduling receipts, fenced with session cleanup; no appointment display content. */
class AppointmentReminderPlansPreferences(context: Context) : AppointmentReminderPlans {
  private val preferences =
      context.applicationContext.getSharedPreferences(
          "appointment_reminder_plans",
          Context.MODE_PRIVATE,
      )

  override fun read(ownerUid: String): Map<String, AppointmentReminderPlan> =
      SessionAccess.immediate {
        try {
          decode(preferences.getString(ownerUid, null) ?: "{}")
        } catch (error: Exception) {
          if (error !is JSONException && error !is DateTimeParseException) throw error
          clearCorruptReceipts(ownerUid)
        }
      }

  private fun decode(raw: String): Map<String, AppointmentReminderPlan> {
    val data = JSONObject(raw)
    return data.keys().asSequence().associateWith { id ->
      val entry = data.getJSONObject(id)
      AppointmentReminderPlan(
          Instant.parse(entry.getString("due")),
          entry.getString("timing"),
          entry.getString("content"),
      )
    }
  }

  private fun clearCorruptReceipts(ownerUid: String): Map<String, AppointmentReminderPlan> {
    // Receipts are reconstructible scheduling metadata. Reset only the damaged account, on disk.
    write(ownerUid, emptyMap())
    return emptyMap()
  }

  override fun write(ownerUid: String, plans: Map<String, AppointmentReminderPlan>) =
      SessionAccess.immediate {
        preferences.edit {
          if (plans.isEmpty()) remove(ownerUid)
          else {
            val data = JSONObject()
            plans.forEach { (id, plan) ->
              data.put(
                  id,
                  JSONObject()
                      .put("due", plan.due.toString())
                      .put("timing", plan.timing)
                      .put("content", plan.content),
              )
            }
            putString(ownerUid, data.toString())
          }
          // Check synchronous persistence before KTX's final no-op apply.
          check(commit()) { "Could not persist appointment reminder plans" }
        }
      }
}

/** Manual injection boundary; installation is deliberately left to the activation PR. */
object AppointmentReminderPlansProvider {
  @Volatile private var current: AppointmentReminderPlans? = null

  /** The installed receipt store, replaceable by the shared fake. */
  var repository: AppointmentReminderPlans
    get() = checkNotNull(current) { "Appointment reminder plans were not installed" }
    set(value) {
      current = value
    }

  /** Restores the uninstalled state between provider tests. */
  @VisibleForTesting
  internal fun reset() {
    current = null
  }

  /** Installs once with application context and preserves injected test stores. */
  @Synchronized
  fun install(context: Context) {
    if (current == null) current = AppointmentReminderPlansPreferences(context)
  }
}
