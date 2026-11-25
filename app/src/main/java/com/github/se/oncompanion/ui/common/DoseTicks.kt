// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.util.Log
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.signedInUid
import com.github.se.oncompanion.model.medication.TakenDosesRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The doses a patient ticked on one day, their new ticks and the sheet correcting them, for a
 * screen where they tick them (the Overview's medications card). Each screen's ViewModel keeps one,
 * so the rules are written once: what is unknown can't be ticked, a tick shows at once before it is
 * saved, and the sheet saves only on Done.
 *
 * The screen passes the medications it shows ([TodayMedication], built with [taken]); this class
 * keeps no list of its own.
 *
 * @param scope where it reads and saves, e.g. the ViewModel's
 * @param repository where the doses are read and saved
 * @param auth who is signed in when a tick is saved: nobody, nothing is saved
 * @param onChange called when [taken] or [sheet] changes, so the screen shows them
 */
class DoseTicks(
    private val scope: CoroutineScope,
    private val repository: TakenDosesRepository,
    private val auth: AuthRepository,
    private val onChange: () -> Unit,
) {
  /**
   * The doses ticked on the day followed, by medication ID, or `null` while they are unknown (not
   * read yet, or they couldn't be): then no medication can be ticked, since a tick saves a count
   * and ticking from a wrong 0 would overwrite the real one.
   */
  var taken: Map<String, Int>? = null
    private set

  /** The sheet correcting a medication's doses, or `null` when it is closed. */
  var sheet: DosesSheet? = null
    private set

  /** Whose doses and which day are followed; `null` when nobody's are (signed out, a caregiver). */
  private var followed: Pair<String, LocalDate>? = null
  private var observation: Job? = null

  /**
   * Follows the doses [uid] ticked on [day], instead of those followed before: until they are read,
   * they are unknown, so the screen drops the previous day's ticks at once, and an open sheet
   * closes unsaved (its draft was for the other day). Without a [uid] (signed out, or a caregiver),
   * or if they can't be read, nothing can be ticked until it follows again.
   *
   * It calls [onChange] at once, so what the screen's `onChange` reads must be ready before the
   * first call.
   */
  fun follow(uid: String?, day: LocalDate) {
    observation?.cancel()
    followed = uid?.let { it to day }
    taken = null
    sheet = null
    onChange()
    if (uid == null) return
    observation = scope.launch {
      repository
          .observeDay(uid, day)
          .catch { e ->
            Log.w(TAG, "Couldn't read the doses taken today", e)
            taken = null
            onChange()
          }
          .collect {
            taken = it
            onChange()
          }
    }
  }

  /**
   * Ticks a dose of [medication]: the next one, up to its doses a day, or, for one taken once a day
   * that is already ticked, unticks it. Nothing happens without doses to tick.
   */
  fun tick(medication: TodayMedication?) {
    val count = medication?.nextTakenCount() ?: return
    save(medication.medicationId, count)
  }

  /**
   * Opens the sheet to correct the doses of [medication], from the count saved. Only for one taken
   * several times a day whose ticks are known: one taken once a day toggles with [tick].
   */
  fun openSheet(medication: TodayMedication?) {
    if (taken == null) return
    sheet = medication.dosesSheet() ?: return
    onChange()
  }

  /** One dose fewer in the sheet's draft, not below 0. */
  fun lessDoses() = changeDraft(-1)

  /** One dose more in the sheet's draft, up to the doses a day. */
  fun moreDoses() = changeDraft(1)

  private fun changeDraft(by: Int) {
    sheet = sheet?.withDraftChangedBy(by) ?: return
    onChange()
  }

  /**
   * Done: closes the sheet and saves its draft, if it differs from the count saved now (another
   * phone may have ticked since it opened).
   */
  fun saveSheet() {
    val done = sheet ?: return
    closeSheet()
    done.changedCount?.let { save(done.medication.medicationId, it) }
  }

  /** Closes the sheet without saving: swiped down, Back, or a tap outside it. */
  fun closeSheet() {
    if (sheet == null) return
    sheet = null
    onChange()
  }

  /**
   * Keeps the sheet only while its medication is still among [medications] with several doses to
   * tick (a caregiver can delete the prescription, or its frequency can change), its draft within
   * the new count. The screen calls it each time it builds its medications; it doesn't call
   * [onChange], since the screen is already updating.
   */
  fun keepSheetFor(medications: List<TodayMedication>) {
    sheet = sheet?.let { open ->
      medications.find { it.medicationId == open.medication.medicationId }.dosesSheet(open.draft)
    }
  }

  /**
   * Saves [count] doses of [medicationId] for the person and day followed, only while they are
   * still the one signed in (a count built on one account's doses never goes to another). It shows
   * at once, so a second quick tap builds on this one instead of saving the same count again; the
   * day's next snapshot confirms it, or brings back the saved count if it's rejected. While the
   * counts are unknown it saves nothing: [count] would have been built on a wrong number.
   */
  private fun save(medicationId: String, count: Int) {
    val (uid, day) = followed ?: return
    if (auth.signedInUid(TAG) != uid) return
    val current = taken ?: return
    taken = current + (medicationId to count)
    onChange()
    scope.launch {
      try {
        repository.setTaken(uid, day, medicationId, count)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Couldn't save the doses taken", e)
      }
    }
  }

  private companion object {
    const val TAG = "DoseTicks"
  }
}
