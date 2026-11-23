// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import com.github.se.oncompanion.model.medication.TakenDosesRepository.Companion.MAX_TAKEN_PER_DAY
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [TakenDosesRepository] for ViewModel unit tests (no Firebase), following its contract:
 * [setTaken] refuses a blank ID or a count outside 0..[MAX_TAKEN_PER_DAY], and changes only that
 * medication on that day; [observeDay] emits the day's counts at once and after each change, an
 * empty map for a day without ticks.
 *
 * @property readFailure if set, [observeDay] fails with it, like a read the rules refuse
 */
class FakeTakenDosesRepository(var readFailure: Exception? = null) : TakenDosesRepository {

  /** One recorded call to [setTaken]. */
  data class Write(val uid: String, val date: LocalDate, val medicationId: String, val count: Int)

  /** The counts saved, by owner and day. */
  val days = MutableStateFlow<Map<Pair<String, LocalDate>, Map<String, Int>>>(emptyMap())

  /** Every [setTaken] call, in order. */
  val writes = mutableListOf<Write>()

  override fun observeDay(uid: String, date: LocalDate): Flow<Map<String, Int>> = flow {
    readFailure?.let { throw it }
    emitAll(days.map { it[uid to date].orEmpty() }.distinctUntilChanged())
  }

  override suspend fun setTaken(uid: String, date: LocalDate, medicationId: String, count: Int) {
    require(medicationId.isNotBlank()) { "The medication has no ID" }
    require(count in 0..MAX_TAKEN_PER_DAY) { "Invalid number of doses taken: $count" }
    writes += Write(uid, date, medicationId, count)
    days.update { saved ->
      saved + ((uid to date) to (saved[uid to date].orEmpty() + (medicationId to count)))
    }
  }
}
