// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.reminder

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.github.se.oncompanion.model.session.SessionAccess
import java.time.Instant
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
        val data = JSONObject(preferences.getString(ownerUid, null) ?: "{}")
        data.keys().asSequence().associateWith { id ->
          val entry = data.getJSONObject(id)
          AppointmentReminderPlan(
              Instant.parse(entry.getString("due")),
              entry.getString("timing"),
              entry.getString("content"),
          )
        }
      }

  override fun write(ownerUid: String, plans: Map<String, AppointmentReminderPlan>) =
      SessionAccess.immediate {
        val editor = preferences.edit()
        if (plans.isEmpty()) editor.remove(ownerUid)
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
          editor.putString(ownerUid, data.toString())
        }
        check(editor.commit()) { "Could not persist appointment reminder plans" }
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
