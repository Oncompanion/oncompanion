// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Shared observable source for Planning tests, including explicit failures and retry behavior. */
class FakePlanningRepository : PlanningRepository {
  /** The planning every owner sees, unless [itemsOf] gives them their own. */
  val items = MutableStateFlow<List<PlanningItem>>(emptyList())
  /** The planning of each owner given one here, instead of [items]. */
  val itemsOf = mutableMapOf<String, MutableStateFlow<List<PlanningItem>>>()
  /** The range each observation asked for, in order. */
  val ranges = mutableListOf<PlanningRange>()
  /** Whose planning each observation asked for, in order. */
  val owners = mutableListOf<String>()
  /** The sections each observation asked for, in order. */
  val sections = mutableListOf<Set<CarePermission>>()
  /** Each observation fails, unless [observation] is set. */
  var fail = false
  /** What each observation gives for its range, instead of [items]. */
  var observation: ((PlanningRange) -> Flow<List<PlanningItem>>)? = null

  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> {
    this.sections.add(sections)
    return observe(ownerUid, range)
  }

  private fun observe(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> {
    owners.add(ownerUid)
    ranges.add(range)
    return observation?.invoke(range)
        ?: if (fail) flow { throw IllegalStateException("Read failed") }
        else itemsOf[ownerUid] ?: items
  }
}
