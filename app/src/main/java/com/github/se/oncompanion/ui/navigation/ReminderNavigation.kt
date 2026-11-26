// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy

/** A notification waits for a graph and cannot skip account resolution, sign-in or onboarding. */
internal fun canOpenAppointmentReminder(destination: NavDestination?): Boolean =
    destination != null &&
        destination.hierarchy.none {
          it.route in setOf(Route.AUTH, Route.ONBOARDING, Route.ACCOUNT_CHECK)
        }
