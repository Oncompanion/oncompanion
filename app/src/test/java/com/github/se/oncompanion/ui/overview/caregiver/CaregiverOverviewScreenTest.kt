// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.CarePermission.PLANNING
import com.github.se.oncompanion.model.carecircle.CarePermission.PRESCRIPTIONS
import com.github.se.oncompanion.model.carecircle.CarePermission.SYMPTOMS
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.symptom.SymptomType.OTHER
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.overview.NextUp
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import com.github.se.oncompanion.ui.overview.TodayItemKind
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.PRIVATE
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.SHOWN
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.StoneCardDark
import com.github.se.oncompanion.ui.theme.StoneCardLight
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class CaregiverOverviewScreenTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** Saturday 3 October 2026, 9:00. */
  private val morning = LocalDateTime.of(2026, 10, 3, 9, 0)

  private val yoga =
      NextUp(
          TodayItemKind.EVENT,
          "yoga",
          "Gentle yoga for patients",
          morning.withHour(10),
          "Wellness center",
      )

  private fun sections(vararg private: CarePermission) =
      listOf(PLANNING, PRESCRIPTIONS, SYMPTOMS).associateWith {
        if (it in private) PRIVATE else SHOWN
      }

  private val shared =
      CaregiverOverviewUiState(
          now = morning,
          firstName = "Marc",
          nextUp = yoga,
          sections = sections(),
          isLoading = false,
      )

  /** An entry the patient logged at [at], as the ViewModel gives it to the card. */
  private fun latest(
      at: LocalDateTime = morning.minusDays(1).withHour(18).withMinute(10),
      symptoms: List<Pair<SymptomType, Int?>> = listOf(NAUSEA to 7, FATIGUE to 6),
      overall: Int? = null,
      otherName: String? = null,
  ) =
      LatestEntry(
          SymptomEntry(
              types = symptoms.map { it.first },
              occurredAt = at.toInstant(ZoneOffset.UTC),
              otherName = otherName,
          ),
          at,
          symptoms,
          overall,
      )

  private fun setContent(
      state: CaregiverOverviewUiState,
      actions: CaregiverOverviewActions = CaregiverOverviewActions(),
  ) {
    compose.setContent { CaregiverOverviewContent(state, "Alex", actions) }
  }

  private fun assertTextInside(text: String, tag: String) {
    compose
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        .assertExists()
  }

  // ----- States -----

  @Test
  fun header_greetsTheCaregiver_andTheDayIsThePatients() {
    setContent(shared)

    compose.onNodeWithTag(C.Tag.overview_greeting).assertIsDisplayed()
    compose.onNodeWithText("Good morning, Marc").assertIsDisplayed()
    compose.onNodeWithText("Alex's day").assertIsDisplayed()
  }

  @Test
  fun eachState_showsOnlyItsOwnContent() {
    var state by mutableStateOf(shared)
    compose.setContent { CaregiverOverviewContent(state, "Alex", CaregiverOverviewActions()) }
    val day =
        listOf(
            C.Tag.caregiver_overview_loading,
            C.Tag.caregiver_overview_error,
            C.Tag.overview_next_up,
            C.Tag.overview_nothing_planned,
        )
    val cases =
        listOf(
            shared.copy(isLoading = true) to C.Tag.caregiver_overview_loading,
            // An error wins over a loading left from before
            shared.copy(isLoading = true, hasError = true) to C.Tag.caregiver_overview_error,
            shared to C.Tag.overview_next_up,
            shared.copy(nextUp = null) to C.Tag.overview_nothing_planned,
        )
    for ((shown, expected) in cases) {
      state = shown
      compose.waitForIdle()
      day.forEach { tag ->
        val node = compose.onNodeWithTag(tag)
        if (tag == expected) node.assertExists() else node.assertDoesNotExist()
      }
      // Everything is shared: no banner, no locked card
      compose.onNodeWithTag(C.Tag.caregiver_overview_shared_only).assertDoesNotExist()
    }
  }

  @Test
  fun error_saysWhoseDay_andRetryReloads() {
    var retried = 0
    setContent(shared.copy(hasError = true), CaregiverOverviewActions(onRetry = { retried++ }))

    compose.onNodeWithText("Couldn't load Alex's day").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.caregiver_overview_retry).performClick()

    assertEquals(1, retried)
  }

  @Test
  fun partlyShared_showsALockedCardPerPrivateSection_andNothingOfItsData() {
    // Even if a Next up were left in the state, a private planning shows none of it
    setContent(shared.copy(sections = sections(PLANNING, SYMPTOMS)))

    compose
        .onNodeWithText("You see what Alex chose to share. Only Alex can change it.")
        .assertIsDisplayed()
    assertTextInside("Planning", C.Tag.caregiverOverviewPrivate(PLANNING.name))
    assertTextInside("Alex keeps this private", C.Tag.caregiverOverviewPrivate(PLANNING.name))
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(SYMPTOMS.name)).assertExists()
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PRESCRIPTIONS.name)).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.overview_next_up).assertDoesNotExist()
    compose.onNodeWithText(yoga.title, substring = true).assertDoesNotExist()
  }

  // ----- The journal -----

  @Test
  fun journal_saysWhenTheNewestEntryWasLogged_andWhat() {
    var state by mutableStateOf(shared)
    compose.setContent { CaregiverOverviewContent(state, "Alex", CaregiverOverviewActions()) }
    val cases =
        listOf(
            latest() to listOf("Yesterday at 18:10", "Nausea 7/10 · Fatigue 6/10"),
            latest(morning.withHour(8).withMinute(30)) to listOf("Today at 08:30"),
            latest(morning.minusDays(17).withHour(18).withMinute(10)) to
                listOf("Wed 16 Sep at 18:10"),
            latest(morning.minusYears(1)) to listOf("Fri 3 Oct 2025 at 09:00"),
            // Saved before a strength per symptom: the entry's one intensity, once
            latest(symptoms = listOf(NAUSEA to null, FATIGUE to null), overall = 6) to
                listOf("Nausea, Fatigue · 6/10"),
            latest(symptoms = listOf(NAUSEA to null, FATIGUE to null)) to listOf("Nausea, Fatigue"),
            // "Other" by the name it was given, or "Other" in older entries
            latest(symptoms = listOf(OTHER to 4), otherName = "Hiccups") to listOf("Hiccups 4/10"),
            latest(symptoms = listOf(OTHER to 4)) to listOf("Other 4/10"),
        )
    for ((entry, texts) in cases) {
      state = shared.copy(latestEntry = entry)
      compose.waitForIdle()
      texts.forEach { assertTextInside(it, C.Tag.caregiver_overview_journal) }
    }
  }

  @Test
  fun emptyJournal_saysNoEntriesYet_andStillOpensTheJournal() {
    var opened = 0
    setContent(shared, CaregiverOverviewActions(onOpenJournal = { opened++ }))

    assertTextInside("Journal", C.Tag.caregiver_overview_journal)
    assertTextInside("No entries yet", C.Tag.caregiver_overview_journal)
    compose.onNodeWithTag(C.Tag.caregiver_overview_full_journal).performScrollTo().performClick()

    assertEquals(1, opened)
  }

  @Test
  fun privateSymptoms_showTheLockedCard_andNothingOfTheEntry() {
    // Even if an entry were left in the state
    setContent(shared.copy(sections = sections(SYMPTOMS), latestEntry = latest()))

    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(SYMPTOMS.name)).assertExists()
    compose.onNodeWithTag(C.Tag.caregiver_overview_journal).assertDoesNotExist()
    compose.onNodeWithText("Nausea", substring = true).assertDoesNotExist()
  }

  @Test
  fun nextUp_opensItsEventOrAppointment_andPlanningOpensPlanning() {
    val opened = mutableListOf<String>()
    var state by mutableStateOf(shared)
    val actions =
        CaregiverOverviewActions(
            onOpenPlanning = { opened += "planning" },
            onOpenAppointment = { opened += "appointment $it" },
            onOpenEvent = { opened += "event $it" },
        )
    compose.setContent { CaregiverOverviewContent(state, "Alex", actions) }

    compose.onNodeWithTag(C.Tag.overview_next_up).performClick()
    state = shared.copy(nextUp = yoga.copy(kind = TodayItemKind.APPOINTMENT, id = "chemo"))
    compose.onNodeWithTag(C.Tag.overview_next_up).performClick()
    compose.onNodeWithTag(C.Tag.overview_next_up_planning_link).performClick()

    assertEquals(listOf("event yoga", "appointment chemo", "planning"), opened)
  }

  @Test
  fun header_switchesPatient_andOpensTheProfile() {
    val opened = mutableListOf<String>()
    setContent(
        shared,
        CaregiverOverviewActions(
            onSwitchPatient = { opened += "whose care" },
            onOpenProfile = { opened += "profile" },
        ),
    )

    compose.onNodeWithTag(C.Tag.overview_whose_care).performClick()
    compose.onNodeWithTag(C.Tag.overview_shortcut_profile).performClick()

    assertEquals(listOf("whose care", "profile"), opened)
  }

  @Test
  fun sharedSections_keepTheirTiles_privateOnesDont() {
    val opened = mutableListOf<OverviewShortcut>()
    var state by mutableStateOf(shared.copy(sections = sections(SYMPTOMS)))
    compose.setContent {
      CaregiverOverviewContent(
          state,
          "Alex",
          CaregiverOverviewActions(onOpenShortcut = { opened += it }),
      )
    }

    compose.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertDoesNotExist()
    compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).performScrollTo().performClick()
    // Never the care circle, which only the patient manages
    compose.onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag).assertDoesNotExist()
    assertEquals(listOf(OverviewShortcut.PRESCRIPTIONS), opened)

    state = shared.copy(sections = sections(PLANNING, PRESCRIPTIONS, SYMPTOMS))
    compose.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()

    // Shared symptoms have the journal card, which opens them: no tile for them
    state = shared.copy(sections = sections(PLANNING, PRESCRIPTIONS))
    compose.onNodeWithTag(C.Tag.caregiver_overview_journal).assertExists()
    compose.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()
  }

  // ----- Layout -----

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_theDayAndItsActionsCanBeReached() {
    var state by
        mutableStateOf(shared.copy(sections = sections(PRESCRIPTIONS), latestEntry = latest()))
    compose.setContent { CaregiverOverviewContent(state, "Alex", CaregiverOverviewActions()) }

    listOf(
            C.Tag.overview_next_up,
            C.Tag.caregiverOverviewPrivate(PRESCRIPTIONS.name),
            C.Tag.caregiver_overview_journal,
        )
        .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }

    state = shared.copy(hasError = true)
    compose.onNodeWithTag(C.Tag.caregiver_overview_retry).performScrollTo().assertIsFullyVisible()
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun largestFont_everyCardIsWhole_onceScrolledTo() {
    setContent(shared.copy(sections = sections(PRESCRIPTIONS), latestEntry = latest()))

    listOf(
            C.Tag.overview_whose_care,
            C.Tag.caregiver_overview_shared_only,
            C.Tag.overview_next_up,
            C.Tag.caregiverOverviewPrivate(PRESCRIPTIONS.name),
            C.Tag.caregiver_overview_journal,
            C.Tag.caregiver_overview_full_journal,
        )
        .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun lockedCard_isAStoneCard_inBothThemes() {
    var dark by mutableStateOf(false)
    compose.setContent {
      OncompanionTheme(darkTheme = dark) {
        CaregiverOverviewContent(
            shared.copy(sections = sections(PLANNING)),
            "Alex",
            CaregiverOverviewActions(),
        )
      }
    }
    for ((isDark, card) in listOf(false to StoneCardLight, true to StoneCardDark)) {
      dark = isDark
      compose.waitForIdle()
      // In the bottom padding, inside the rounded corner and away from the lock and the text
      val node = compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PLANNING.name))
      val height = with(compose.density) { node.fetchSemanticsNode().size.height.toDp() }
      assertSameColor("locked card, dark=$isDark", card, node.colorAt(24.dp, height - 6.dp))
    }
  }

  // ----- With its ViewModel -----

  @Test
  fun phoneRotated_thePatientsDayIsStillShown_andAPrivateSectionStaysHidden() {
    val patient = FollowedPatient(PLANNING, SYMPTOMS)
    val zone = ZoneOffset.UTC
    val planning =
        FakePlanningRepository().apply {
          items.value =
              listOf(
                  PlanningItem(
                      PlanningSource.Event("yoga"),
                      PlanningTiming.Timed(morning.withHour(10).toInstant(zone)),
                      yoga.title,
                  )
              )
        }
    val viewModel =
        CaregiverOverviewViewModel(
            patientUid = "patient",
            authRepository = patient.auth,
            profileRepository = patient.profiles,
            observePlanning = ObserveCarePlanning(patient.sectionAccess, planning, patient.circle),
            symptomRepository =
                FakeSymptomRepository().apply {
                  seed(
                      "patient",
                      SymptomEntry(
                          id = "nausea",
                          types = listOf(NAUSEA),
                          occurredAt = morning.withHour(8).toInstant(zone),
                          strengths = mapOf(NAUSEA to 7),
                      ),
                  )
                },
            observeSection = ObserveCareSection(patient.sectionAccess),
            resolveAccess = patient.careAccess,
            clock = Clock.fixed(morning.toInstant(zone), zone),
            zoneId = zone,
        )
    val show = {
      compose.activity.setContent {
        CaregiverOverviewScreen(
            viewModel = viewModel,
            patientName = "Alex",
            navigationActions = NavigationActions(TestNavHostController(LocalContext.current)),
            onOpenAppointment = {},
            onOpenEvent = {},
            onSwitchPatient = {},
        )
      }
    }
    compose.runOnUiThread(show)
    assertTextInside(yoga.title, C.Tag.overview_next_up)
    assertTextInside("Nausea 7/10", C.Tag.caregiver_overview_journal)
    compose.onNodeWithText("Good morning, Sam").assertIsDisplayed()
    // A caregiver's bottom bar has no Prescriptions tab: they open the shared list from here
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_overview).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_prescriptions).assertDoesNotExist()

    // Rotating recreates the activity; the ViewModel survives it
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread(show)
    assertTextInside(yoga.title, C.Tag.overview_next_up)
    assertTextInside("Nausea 7/10", C.Tag.caregiver_overview_journal)

    // The patient stops sharing their planning, then their symptoms, while the Overview is open
    patient.shares(SYMPTOMS)
    ShadowLooper.idleMainLooper()
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PLANNING.name)).assertIsDisplayed()
    compose.onNodeWithText(yoga.title).assertDoesNotExist()
    patient.shares()
    ShadowLooper.idleMainLooper()
    compose
        .onNodeWithTag(C.Tag.caregiverOverviewPrivate(SYMPTOMS.name))
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Nausea 7/10").assertDoesNotExist()
  }
}
