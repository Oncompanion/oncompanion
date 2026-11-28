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
}
