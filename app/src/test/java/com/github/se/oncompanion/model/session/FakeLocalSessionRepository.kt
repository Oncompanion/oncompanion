// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.session

import com.github.se.oncompanion.domain.auth.CleanupJournal

/** Local lifecycle fake with controllable acknowledgement and cleanup failures. */
class FakeLocalSessionRepository : LocalSessionRepository {
  /** The names of the methods called, in order (e.g. "freeze", "seal"). */
  val calls = mutableListOf<String>()
  /** Whether new writes are accepted: false between freeze and resume or reopen. */
  var accepting = true
  /** Whether the session was sealed and not reopened since. */
  var sealed = false
  /** Whether changes wait to be saved online; synchronize and clear empty them. */
  var offlineChanges = true
  /** What synchronize does before it ends, e.g. suspend or throw. */
  var synchronizeAction: suspend () -> Unit = {}
  /** Thrown by clear, if set. */
  var clearError: Exception? = null
  /** Thrown by seal, if set. */
  var sealError: Exception? = null

  override fun prepare() {}

  override suspend fun freeze() {
    calls += "freeze"
    accepting = false
  }

  override suspend fun synchronize() {
    calls += "synchronize"
    synchronizeAction()
    offlineChanges = false
  }

  override fun resume() {
    calls += "resume"
    accepting = true
  }

  override suspend fun seal() {
    calls += "seal"
    sealed = true
    sealError?.let { throw it }
  }

  override suspend fun clear() {
    calls += "clear"
    clearError?.let { throw it }
    offlineChanges = false
  }

  override fun reopen() {
    calls += "reopen"
    sealed = false
    accepting = true
  }
}

/** In-memory journal fake, including durable-write failure injection. */
class FakeCleanupJournal(pending: Boolean = false) : CleanupJournal {
  private var flag = pending
  override val pending: Boolean
    get() = flag

  /** Thrown by setPending, if set: the journal couldn't be written. */
  var failure: Exception? = null

  override fun setPending(pending: Boolean) {
    failure?.let { throw it }
    flag = pending
  }
}
