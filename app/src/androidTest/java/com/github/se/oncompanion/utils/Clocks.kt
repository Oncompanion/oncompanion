// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A clock fixed at noon on [day], for tests that seed items on [day] and then open a screen that
 * starts from today: a run crossing midnight can't open it on another day.
 */
fun noonOn(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Clock =
    Clock.fixed(day.atTime(12, 0).atZone(zone).toInstant(), zone)
