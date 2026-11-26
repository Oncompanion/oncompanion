// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavigatorProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A reminder waits for account setup, including while the graph has no current destination. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNavigationTest {
  @Test
  fun tapWaitsForGraphAndEveryAccountFlow_includingItsChildScreens() {
    assertFalse(canOpenAppointmentReminder(null))
    for (route in listOf(Route.AUTH, Route.ONBOARDING, Route.ACCOUNT_CHECK)) {
      val graph = NavGraph(NavGraphNavigator(NavigatorProvider())).apply { this.route = route }
      val child = NavDestination("test").apply { this.route = "child" }
      graph.addDestination(child)
      assertFalse(canOpenAppointmentReminder(graph))
      assertFalse(canOpenAppointmentReminder(child))
    }
    for (route in listOf(Route.OVERVIEW, Route.PLANNING)) {
      assertTrue(canOpenAppointmentReminder(NavDestination("test").apply { this.route = route }))
    }
  }
}
