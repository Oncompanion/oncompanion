// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * When a caregiver last had each person's day open, kept on the phone only, like
 * [SeenPrescriptions][com.github.se.oncompanion.model.medication.SeenPrescriptions]: what the
 * person logged since is "new" in "Whose care?". Nothing goes to the server, so it works offline
 * and the patient's data stays theirs to write.
 */
interface LastOpened {

  /**
   * When [caregiverUid] last had each person's day open, by patient uid, now and at each change.
   */
  fun observe(caregiverUid: String): Flow<Map<String, Instant>>

  /**
   * [caregiverUid] had [patientUid]'s day open at [at]. A time before the one remembered is
   * ignored: a phone clock behind the server's must not make what was seen new again.
   */
  fun markOpened(caregiverUid: String, patientUid: String, at: Instant)

  companion object {
    /**
     * How a time is stored: per caregiver, so the next account to sign in inherits none. A uid
     * never holds a "/": it names a Firestore document (`/users/{uid}`).
     */
    fun key(caregiverUid: String, patientUid: String) = "$caregiverUid/$patientUid"
  }
}

/** [LastOpened] in the phone's [preferences]; it only holds user IDs and times. */
class LastOpenedPreferences(private val preferences: SharedPreferences) : LastOpened {

  // Changes since the app started, so every screen reading it sees a new one at once
  private val changes = MutableStateFlow(0)

  override fun observe(caregiverUid: String): Flow<Map<String, Instant>> = changes.map {
    val prefix = LastOpened.key(caregiverUid, "")
    preferences.all
        .mapNotNull { (key, value) ->
          if (key.startsWith(prefix) && value is Long) {
            key.removePrefix(prefix) to Instant.ofEpochMilli(value)
          } else null
        }
        .toMap()
  }

  override fun markOpened(caregiverUid: String, patientUid: String, at: Instant) {
    val key = LastOpened.key(caregiverUid, patientUid)
    if (at.toEpochMilli() <= preferences.getLong(key, Long.MIN_VALUE)) return
    preferences.edit { putLong(key, at.toEpochMilli()) }
    changes.update { it + 1 }
  }
}

/** [LastOpened] in memory, until MainActivity installs the phone's. Tests use it as it is. */
class LastOpenedInMemory : LastOpened {
  private val opened = MutableStateFlow<Map<String, Map<String, Instant>>>(emptyMap())

  override fun observe(caregiverUid: String): Flow<Map<String, Instant>> = opened.map {
    it[caregiverUid].orEmpty()
  }

  override fun markOpened(caregiverUid: String, patientUid: String, at: Instant) {
    opened.update {
      val theirs = it[caregiverUid].orEmpty()
      val latest = theirs[patientUid]?.let { before -> maxOf(before, at) } ?: at
      it + (caregiverUid to theirs + (patientUid to latest))
    }
  }
}

/** Holds the app's [LastOpened]; MainActivity installs the phone's. */
object LastOpenedProvider {
  @Volatile var store: LastOpened = LastOpenedInMemory()
}
