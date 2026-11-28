// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.carecircle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverEditSessionTest {
  @Test
  fun aCompletionFromBeforeRevocationStaysInvalidAfterRegrant() {
    val session = CaregiverEditSession()
    assertFalse(session.permits("caregiver"))
    session.grant("caregiver")
    val pending = session.generation
    session.grant("caregiver")
    assertTrue(session.permits("caregiver", pending))

    session.invalidate()
    assertFalse(session.permits("caregiver", pending))
    session.grant("caregiver")
    assertFalse(session.permits("caregiver", pending))
    assertTrue(session.permits("caregiver"))
  }

  @Test
  fun switchingActorsInvalidatesThePreviousGrant() {
    val session = CaregiverEditSession()
    session.grant("first")
    val pending = session.generation
    session.grant("second")
    assertFalse(session.permits("first", pending))
    assertFalse(session.permits("second", pending))
    assertTrue(session.permits("second"))
  }

  /** Only the account that started the mutation can use the original grant. */
  @Test
  fun currentAccountMustMatchTheCapturedActor() {
    val session = CaregiverEditSession()
    session.grant("caregiver")
    val attempt = session.generation
    assertFalse(session.permitsCurrent(null, "caregiver", attempt))
    assertFalse(session.permitsCurrent("other", "caregiver", attempt))
    assertTrue(session.permitsCurrent("caregiver", "caregiver", attempt))
    session.invalidate()
    assertFalse(session.permitsCurrent("caregiver", "caregiver", attempt))
  }
}
