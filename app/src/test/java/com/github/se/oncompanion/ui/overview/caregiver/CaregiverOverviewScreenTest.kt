// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview.caregiver

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
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
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.medication.DoseCount
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.CarePermission.PLANNING
import com.github.se.oncompanion.model.carecircle.CarePermission.PRESCRIPTIONS
import com.github.se.oncompanion.model.carecircle.CarePermission.SYMPTOMS
import com.github.se.oncompanion.model.medication.FakeTakenDosesRepository
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
import com.github.se.oncompanion.ui.overview.TodayMedication
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.PRIVATE
import com.github.se.oncompanion.ui.overview.caregiver.SectionVisibility.SHOWN
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.StoneCardDark
import com.github.se.oncompanion.ui.theme.StoneCardLight
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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

  /** A medication of today's, as the ViewModel gives it to the card. */
  private fun todays(name: String, frequency: String, taken: Int = 0) =
      TodayMedication(
          key = "medication:$name",
          medicationId = name,
          name = "$name 8 mg",
          frequency = frequency,
          dailyDoses = DoseCount.describe(frequency),
          dosesPerDay = DoseCount.of(frequency),
          taken = taken,
      )

  /** One taken several times a day and partly taken, one once a day, one only if needed. */
  private val medications =
      listOf(
          todays("Ondansetron", "1-1-1", taken = 1),
          todays("Dexamethasone", "1-0-0"),
          todays("Paracetamol", "si besoin"),
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
  fun nothingUpcoming_saysNothingElseToday_onceTheDayHasStarted() {
    var state by mutableStateOf(shared.copy(nextUp = null))
    compose.setContent { CaregiverOverviewContent(state, "Alex", CaregiverOverviewActions()) }
    val nothingPlanned = compose.activity.getString(R.string.overview_nothing_today)
    val nothingElse = compose.activity.getString(R.string.overview_nothing_else_today)

    compose.onNodeWithText(nothingPlanned).assertIsDisplayed()
    compose.onNodeWithText(nothingElse).assertDoesNotExist()

    state = state.copy(dayStarted = true)
    compose.waitForIdle()
    compose.onNodeWithText(nothingElse).assertIsDisplayed()
    compose.onNodeWithText(nothingPlanned).assertDoesNotExist()
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
            // A strength for some symptoms only: the others by their name alone
            latest(symptoms = listOf(NAUSEA to null, FATIGUE to 4)) to
                listOf("Nausea · Fatigue 4/10"),
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
  fun journalEmptiedWhileOpen_theCardSaysNoEntriesYet_andStillOpensTheJournal() {
    var state by mutableStateOf(shared.copy(latestEntry = latest()))
    var opened = 0
    compose.setContent {
      CaregiverOverviewContent(
          state,
          "Alex",
          CaregiverOverviewActions(onOpenJournal = { opened++ }),
      )
    }
    assertTextInside("Nausea 7/10 · Fatigue 6/10", C.Tag.caregiver_overview_journal)

    state = shared
    compose.waitForIdle()

    assertTextInside("Journal", C.Tag.caregiver_overview_journal)
    assertTextInside("No entries yet", C.Tag.caregiver_overview_journal)
    compose.onNodeWithText("Nausea 7/10 · Fatigue 6/10").assertDoesNotExist()
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
    compose
        .onNodeWithText(compose.activity.getString(R.string.care_circle_title))
        .assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.circle_button).assertDoesNotExist()
    assertEquals(listOf(OverviewShortcut.PRESCRIPTIONS), opened)

    state = shared.copy(sections = sections(PLANNING, PRESCRIPTIONS, SYMPTOMS))
    compose.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()

    // Shared symptoms have the journal card, which opens them: no tile for them
    state = shared.copy(sections = sections(PLANNING, PRESCRIPTIONS))
    compose.onNodeWithTag(C.Tag.caregiver_overview_journal).assertExists()
    compose.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()
  }

  // ----- Today's medications -----

  @Test
  fun medications_readOnly_sayTheDosesTaken_andOpenThePrescriptions() {
    val opened = mutableListOf<OverviewShortcut>()
    setContent(
        shared.copy(todayMedications = medications.take(2)),
        CaregiverOverviewActions(onOpenShortcut = { opened += it }),
    )

    assertTextInside("Medications today", C.Tag.overview_medications)
    assertTextInside("3 times a day", C.Tag.overviewMedication("medication:Ondansetron"))
    assertTextInside("1 of 3", C.Tag.overviewMedication("medication:Ondansetron"))
    // Once a day too: no button says it on the caregiver's card
    assertTextInside("Once a day", C.Tag.overviewMedication("medication:Dexamethasone"))
    assertTextInside("0 of 1", C.Tag.overviewMedication("medication:Dexamethasone"))
    for (medication in medications.take(2)) {
      compose.onNodeWithTag(C.Tag.overviewDoseButton(medication.key)).assertDoesNotExist()
      // Nothing to tap, nor anything TalkBack would announce as a button
      compose.onNodeWithTag(C.Tag.overviewMedication(medication.key)).assertHasNoClickAction()
    }
    compose
        .onNode(
            hasTestTag(C.Tag.overviewMedication("medication:Ondansetron")) and
                hasContentDescription("1 of 3 doses taken today", substring = true)
        )
        .assertExists()
    // The card opens the prescriptions, so their tile goes
    compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.caregiver_overview_prescriptions_link).performClick()
    assertEquals(listOf(OverviewShortcut.PRESCRIPTIONS), opened)
  }

  @Test
  fun medications_ifNeededOrWithoutTheirDoses_showNoBarNorCount() {
    // "If needed", and doses that couldn't be read (the ViewModel gives none to count)
    val withoutDoses = todays("Ondansetron", "1-1-1").copy(dosesPerDay = null)
    setContent(shared.copy(todayMedications = listOf(medications[2], withoutDoses)))

    // Taken only if needed: its chip, and nothing to count
    assertTextInside("If needed", C.Tag.overviewMedication("medication:Paracetamol"))
    assertTextInside("3 times a day", C.Tag.overviewMedication("medication:Ondansetron"))
    compose.onNodeWithText(" of ", substring = true).assertDoesNotExist()
  }

  @Test
  fun manyMedications_countThemInTheTitle_andMoreOpensThePrescriptions() {
    val opened = mutableListOf<OverviewShortcut>()
    setContent(
        shared.copy(todayMedications = medications + todays("Lorazepam", "0-0-1")),
        CaregiverOverviewActions(onOpenShortcut = { opened += it }),
    )

    assertTextInside("Medications today · 4", C.Tag.overview_medications)
    compose.onNodeWithText("Paracetamol 8 mg").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.overview_more_medications).performScrollTo().performClick()
    assertEquals(listOf(OverviewShortcut.PRESCRIPTIONS), opened)
  }

  @Test
  fun medications_showOnlyWhileThePrescriptionsAreShared_andOnADayWithSome() {
    var state by
        mutableStateOf(
            shared.copy(sections = sections(PRESCRIPTIONS), todayMedications = medications)
        )
    compose.setContent { CaregiverOverviewContent(state, "Alex", CaregiverOverviewActions()) }

    // Even if medications were left in the state, private prescriptions show none of them
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PRESCRIPTIONS.name)).assertExists()
    compose.onNodeWithTag(C.Tag.overview_medications).assertDoesNotExist()
    compose.onNodeWithText("Ondansetron 8 mg").assertDoesNotExist()

    // Shared, on a day without medications: no empty card, the tile stays
    state = shared.copy(sections = sections(PLANNING, SYMPTOMS), todayMedications = emptyList())
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.overview_medications).assertDoesNotExist()
    compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertExists()
  }

  @Test
  fun medicationsLoaded_butTheDayFailedOrIsLoading_thePrescriptionsTileStays() {
    val opened = mutableListOf<OverviewShortcut>()
    var state by mutableStateOf(shared.copy(todayMedications = medications))
    compose.setContent {
      CaregiverOverviewContent(
          state,
          "Alex",
          CaregiverOverviewActions(onOpenShortcut = { opened += it }),
      )
    }
    compose.onNodeWithTag(C.Tag.overview_medications).assertExists()
    compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertDoesNotExist()

    // The journal or the planning failed (or is still loading): no medications card, so the tile
    // is the only way left to the prescriptions
    for (day in listOf(shared.copy(hasError = true), shared.copy(isLoading = true))) {
      state = day.copy(todayMedications = medications)
      compose.waitForIdle()
      compose.onNodeWithTag(C.Tag.overview_medications).assertDoesNotExist()
      compose.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).performScrollTo().performClick()
    }
    compose.runOnIdle {
      assertEquals(listOf(OverviewShortcut.PRESCRIPTIONS, OverviewShortcut.PRESCRIPTIONS), opened)
    }
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun largestFont_theMedicationsAreWhole_onceScrolledTo() {
    assertMedicationsReachable()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE, fontScale = 2f)
  fun largestFontInLandscape_theMedicationsAreWhole_onceScrolledTo() {
    assertMedicationsReachable()
  }

  private fun assertMedicationsReachable() {
    setContent(shared.copy(todayMedications = medications.take(2), latestEntry = latest()))

    (listOf(C.Tag.overview_medications, C.Tag.caregiver_overview_prescriptions_link) +
            medications.take(2).map { C.Tag.overviewMedication(it.key) } +
            C.Tag.caregiver_overview_journal)
        .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }
    compose.onNodeWithText("1 of 3").assertExists()
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
    val patient = FollowedPatient(PLANNING, PRESCRIPTIONS, SYMPTOMS)
    val zone = ZoneOffset.UTC
    val medications =
        FakePlanningRepository().apply {
          items.value =
              listOf(
                  PlanningItem(
                      PlanningSource.Medication("prescription", "ondansetron"),
                      PlanningTiming.DateOnly(morning.toLocalDate()),
                      "Ondansetron",
                      frequency = "1-1-1",
                  )
              )
        }
    val doses =
        FakeTakenDosesRepository().apply {
          runBlocking { setTaken("patient", morning.toLocalDate(), "ondansetron", 1) }
        }
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
            medications = medications,
            takenDoses = doses,
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
    assertTextInside("1 of 3", C.Tag.overview_medications)
    assertTextInside("Nausea 7/10", C.Tag.caregiver_overview_journal)
    compose.onNodeWithText("Good morning, Sam").assertIsDisplayed()
    // A caregiver's bottom bar has no Prescriptions tab: they open the shared list from here
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_overview).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.bottom_navigation_tab_prescriptions).assertDoesNotExist()

    // Rotating recreates the activity; the ViewModel survives it
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread(show)
    assertTextInside(yoga.title, C.Tag.overview_next_up)
    assertTextInside("1 of 3", C.Tag.overview_medications)
    assertTextInside("Nausea 7/10", C.Tag.caregiver_overview_journal)

    // The patient stops sharing their planning, then their symptoms, while the Overview is open
    patient.shares(PRESCRIPTIONS, SYMPTOMS)
    ShadowLooper.idleMainLooper()
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PLANNING.name)).assertIsDisplayed()
    compose.onNodeWithText(yoga.title).assertDoesNotExist()
    patient.shares(SYMPTOMS)
    ShadowLooper.idleMainLooper()
    compose.onNodeWithTag(C.Tag.caregiverOverviewPrivate(PRESCRIPTIONS.name)).assertIsDisplayed()
    compose.onNodeWithText("Ondansetron", substring = true).assertDoesNotExist()
    patient.shares()
    ShadowLooper.idleMainLooper()
    compose
        .onNodeWithTag(C.Tag.caregiverOverviewPrivate(SYMPTOMS.name))
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Nausea 7/10").assertDoesNotExist()
  }
}
