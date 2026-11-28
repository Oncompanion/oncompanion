// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.carecircle

/** Tracks the permission under which an editor started a mutation. */
internal class CaregiverEditSession {
  private var actor: String? = null

  /** Identifies the current permission grant across suspension points. */
  var generation: Int = 0
    private set

  /** Allows edits for the actor whose access was confirmed. */
  fun grant(uid: String) {
    if (actor != uid) {
      generation++
      actor = uid
    }
  }

  /** Ends the current grant so pending mutations cannot report back into a later grant. */
  fun invalidate() {
    actor = null
    generation++
  }

  /** Whether the mutation still belongs to the current permission grant. */
  fun permits(uid: String, attempt: Int = generation): Boolean =
      actor == uid && attempt == generation

  /** Checks the captured actor against both the current account and permission grant. */
  fun permitsCurrent(currentUid: String?, expectedUid: String, attempt: Int): Boolean =
      currentUid == expectedUid && permits(expectedUid, attempt)
}
