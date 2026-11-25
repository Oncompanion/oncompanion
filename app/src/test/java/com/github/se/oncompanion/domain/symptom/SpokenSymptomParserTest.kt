// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.APPETITE_LOSS
import com.github.se.oncompanion.model.symptom.SymptomType.BREATHLESSNESS
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.FEVER
import com.github.se.oncompanion.model.symptom.SymptomType.HEADACHE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.symptom.SymptomType.PAIN
import com.github.se.oncompanion.model.symptom.SymptomType.SLEEP_PROBLEMS
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenSymptomParserTest {

  private val today = LocalDate.of(2026, 10, 5)
  /** Monday 5 October 2026 at 14:00 in Zurich. */
  private val now = today.atTime(14, 0).atZone(ZoneId.of("Europe/Zurich"))
  private val yesterday = today.minusDays(1)

  /** What the parser should draft from [transcript]; the notes are always the transcript. */
  private data class Case(
      val transcript: String,
      val types: List<SymptomType> = emptyList(),
      val intensity: Int? = null,
      val date: LocalDate? = null,
      val time: LocalTime? = null,
  )

  private fun check(locale: Locale, cases: List<Case>) {
    for (case in cases) {
      val expected = SymptomDraft(case.types, case.intensity, case.date, case.time, case.transcript)
      assertEquals(case.transcript, expected, parseSpokenSymptom(case.transcript, locale, now))
    }
  }

  @Test
  fun english_draftsWhatWasSaid_andLeavesTheRestEmpty() {
    check(
        Locale.ENGLISH,
        listOf(
            // Symptom words, synonyms and several symptoms at once
            Case("i'm really tired about 7 out of 10 since this morning", listOf(FATIGUE), 7),
            Case("exhausted today no appetite", listOf(FATIGUE, APPETITE_LOSS)),
            Case("my head hurts and I have a fever", listOf(HEADACHE, FEVER)),
            Case("pain in my back eight out of ten", listOf(PAIN), 8),
            Case("I'm not hungry", listOf(APPETITE_LOSS)),
            Case("I couldn't sleep last night", listOf(SLEEP_PROBLEMS)),
            Case("I feel sick 7/10", listOf(NAUSEA), 7),
            Case("I felt tired, tired, so tired", listOf(FATIGUE)),
            // Negation, up to the end of the clause
            Case(
                "I felt nauseous two hours ago but no headache",
                listOf(NAUSEA),
                null,
                today,
                at(12),
            ),
            Case("no nausea or headache just tired", listOf(FATIGUE)),
            Case("I don't feel tired"),
            Case("Not tired, nauseous.", listOf(NAUSEA)),
            Case("I don't know, I'm tired", listOf(FATIGUE)),
            // When it was felt
            Case("short of breath since yesterday", listOf(BREATHLESSNESS), null, yesterday),
            Case("half an hour ago I threw up", listOf(NAUSEA), null, today, at(13, 30)),
            Case("an hour ago", date = today, time = at(13)),
            Case("20 minutes ago", date = today, time = at(13, 40)),
            Case("at 0:30", time = at(0, 30)),
            Case("at 25:00"),
            Case("0 hours ago"),
            Case("headache at 9 am", listOf(HEADACHE), time = at(9)),
            Case("headache at 1:30 p.m.", listOf(HEADACHE), time = at(13, 30)),
            // 9 pm hasn't come yet, so only 9 in the morning is possible
            Case("headache at 9 o'clock", listOf(HEADACHE), time = at(9)),
            // Both 9:00 and 21:00 are possible yesterday: not guessed
            Case("yesterday at 9 o'clock", date = yesterday),
            // A bare number isn't a time, and a time that hasn't come yet is dropped
            Case("headache at 9", listOf(HEADACHE)),
            Case("headache at 3 pm", listOf(HEADACHE)),
            // A day that can't be placed: no time on today either
            Case("headache tomorrow at 9 am", listOf(HEADACHE)),
            Case("headache the day after tomorrow at 9 am", listOf(HEADACHE)),
            Case("headache Monday at 9 am", listOf(HEADACHE)),
            Case("headache next week", listOf(HEADACHE)),
            Case("headache yesterday at 9 am", listOf(HEADACHE), null, yesterday, at(9)),
            // Contradicting times or intensities: not guessed
            Case("yesterday, no, 2 hours ago"),
            Case("2 hours ago or 3 hours ago"),
            Case("yesterday or the day before yesterday"),
            Case("5 out of 10 then 7 out of 10"),
            // Only 1 to 10 is an intensity
            Case("11 out of 10"),
            Case("0 out of 10"),
            // Words that look like a symptom but aren't one
            Case("I'm not so tired"),
            Case("tired of waiting"),
            Case("sick and tired of this"),
            Case("pain free today"),
            Case("no appetite problems"),
            Case("the drain was drained"),
            // Difficulty eating isn't loss of appetite: it stays in the notes
            Case("I can't eat"),
            Case("feeling drained", listOf(FATIGUE)),
            Case("head ache", listOf(HEADACHE)),
            Case("I can't stop throwing up", listOf(NAUSEA)),
            Case("nothing hurts just tired", listOf(FATIGUE)),
            // Nothing understood
            Case("I don't feel great"),
            Case(""),
        ),
    )
  }

  @Test
  fun french_draftsWhatWasSaid_andLeavesTheRestEmpty() {
    check(
        Locale.FRENCH,
        listOf(
            Case("je suis épuisé sept sur dix depuis ce matin", listOf(FATIGUE), 7),
            Case(
                "j'ai eu des nausées il y a deux heures, pas de mal de tête",
                listOf(NAUSEA),
                null,
                today,
                at(12),
            ),
            Case("mal à la tête 8 sur 10", listOf(HEADACHE), 8),
            Case("je n'ai pas faim", listOf(APPETITE_LOSS)),
            Case("je n'ai plus de fièvre mais j'ai mal au ventre", listOf(PAIN)),
            Case("j'ai mal dormi cette nuit", listOf(SLEEP_PROBLEMS)),
            Case("j'ai du mal à respirer", listOf(BREATHLESSNESS)),
            Case("Envie de vomir, 6/10.", listOf(NAUSEA), 6),
            // "pain" is bread in French, and "pas mal" isn't pain
            Case("j'ai mangé du pain"),
            Case("je me sens pas mal"),
            // Words that look like a symptom but aren't one
            Case("on a mangé des crevettes"),
            Case("mon téléphone est à plat"),
            Case("le médicament est épuisé"),
            Case("j'ai plus d'énergie qu'avant"),
            Case("je suis mal à l'aise"),
            Case("j'ai du mal à la comprendre"),
            Case("j'ai un peu dormi"),
            Case("rien ne me fait mal"),
            Case("je suis crevée", listOf(FATIGUE)),
            Case("je n'ai plus d'énergie", listOf(FATIGUE)),
            Case("ça me fait mal à la tête", listOf(HEADACHE)),
            Case("mal de gorge", listOf(PAIN)),
            Case("je n'ai pas fermé l'œil", listOf(SLEEP_PROBLEMS)),
            // "pas mal de" means "quite a lot of", and "je ne sais pas" negates nothing
            Case("j'ai pas mal de fatigue", listOf(FATIGUE)),
            Case("je ne sais pas je suis fatiguée", listOf(FATIGUE)),
            // "plus" only negates after "ne": here it means "more"
            Case("encore plus fatiguée", listOf(FATIGUE)),
            // "il y a" without a duration is "there is"
            Case("il y a des nausées", listOf(NAUSEA)),
            // When it was felt
            Case("essoufflée depuis hier", listOf(BREATHLESSNESS), null, yesterday),
            Case("avant-hier j'avais de la fièvre", listOf(FEVER), null, today.minusDays(2)),
            Case("fatiguée il y a une demi-heure", listOf(FATIGUE), null, today, at(13, 30)),
            Case("nausées il y a 3h", listOf(NAUSEA), null, today, at(11)),
            Case("nausées à 9h", listOf(NAUSEA), time = at(9)),
            Case("nausées à 13h30", listOf(NAUSEA), time = at(13, 30)),
            Case("nausées à 8 heures du soir hier", listOf(NAUSEA), null, yesterday, at(20)),
            Case("nausées à 15h", listOf(NAUSEA)),
            // A day that can't be placed: no time on today either
            Case("nausées demain à 9h", listOf(NAUSEA)),
            Case("nausées après-demain à 9h", listOf(NAUSEA)),
            Case("nausées lundi à 9h", listOf(NAUSEA)),
            Case("nausées la semaine dernière", listOf(NAUSEA)),
            Case("nausées hier à 9h", listOf(NAUSEA), null, yesterday),
            Case("je ne me sens pas très bien"),
        ),
    )
  }

  @Test
  fun english_recognizesCommonWaysOfSayingASymptom() {
    check(
        Locale.ENGLISH,
        listOf(
            // The chip's own name
            Case("I have sleep problems", listOf(SLEEP_PROBLEMS)),
            Case("I can't fall asleep", listOf(SLEEP_PROBLEMS)),
            Case("I woke up several times", listOf(SLEEP_PROBLEMS)),
            Case("I struggle to breathe", listOf(BREATHLESSNESS)),
            Case("I'm struggling to breathe", listOf(BREATHLESSNESS)),
            // The recognizer can drop the final e of "breathe"
            Case("I can't breath", listOf(BREATHLESSNESS)),
            Case("breathing problems", listOf(BREATHLESSNESS)),
            Case("I feel achy", listOf(PAIN)),
            Case("I have a toothache", listOf(PAIN)),
            Case("earache", listOf(PAIN)),
            Case("cramps in my legs", listOf(PAIN)),
            Case("my head is pounding", listOf(HEADACHE)),
            Case("puking all morning", listOf(NAUSEA)),
            Case("zero energy", listOf(FATIGUE)),
            Case("my appetite is gone", listOf(APPETITE_LOSS)),
            // A word between the words of a phrase
            Case("I feel a bit sick", listOf(NAUSEA)),
            Case("I feel really sick", listOf(NAUSEA)),
            Case("I'm not very hungry", listOf(APPETITE_LOSS)),
            Case("I've lost all my appetite", listOf(APPETITE_LOSS)),
            Case("my head really hurts", listOf(HEADACHE)),
            Case("I can't really sleep", listOf(SLEEP_PROBLEMS)),
            // Not a symptom, or not this one
            Case("I didn't sleep badly"),
            Case("I didn't sleep too badly"),
            Case("I slept well"),
            Case("I feel sleepy"),
            Case("bad breath"),
            Case("I can breathe fine"),
        ),
    )
  }

  @Test
  fun french_recognizesCommonWaysOfSayingASymptom() {
    check(
        Locale.FRENCH,
        listOf(
            // The chip's name, as in English
            Case("j'ai des troubles du sommeil", listOf(SLEEP_PROBLEMS)),
            Case("j'ai des problèmes de sommeil", listOf(SLEEP_PROBLEMS)),
            Case("je n'arrive pas à m'endormir", listOf(SLEEP_PROBLEMS)),
            Case("j'ai du mal à m'endormir", listOf(SLEEP_PROBLEMS)),
            Case("réveillée plusieurs fois", listOf(SLEEP_PROBLEMS)),
            Case("j'ai pas assez dormi", listOf(SLEEP_PROBLEMS)),
            Case("j'étouffe", listOf(BREATHLESSNESS)),
            Case("j'ai des difficultés à respirer", listOf(BREATHLESSNESS)),
            Case("je suis à bout de souffle", listOf(BREATHLESSNESS)),
            Case("je n'ai aucune énergie", listOf(FATIGUE)),
            Case("je suis sans énergie", listOf(FATIGUE)),
            Case("j'ai des crampes", listOf(PAIN)),
            Case("j'ai des courbatures", listOf(PAIN)),
            Case("j'ai des haut-le-cœur", listOf(NAUSEA)),
            Case("j'ai perdu l'appétit", listOf(APPETITE_LOSS)),
            Case("j'ai plus envie de manger", listOf(APPETITE_LOSS)),
            Case("aucune envie de manger", listOf(APPETITE_LOSS)),
            // "j'ai mal" is pain, unless it starts a longer phrase
            Case("j'ai mal", listOf(PAIN)),
            Case("j'ai mal, 6 sur 10", listOf(PAIN), 6),
            Case("j'ai très mal depuis hier", listOf(PAIN), null, yesterday),
            Case("j'ai encore mal", listOf(PAIN)),
            Case("j'ai mal à la tête", listOf(HEADACHE)),
            Case("j'ai mal au cœur", listOf(NAUSEA)),
            Case("j'ai mal partout", listOf(PAIN)),
            Case("je n'ai plus mal"),
            // A word between the words of a phrase
            Case("je suis complètement crevée", listOf(FATIGUE)),
            Case("je suis un peu crevée", listOf(FATIGUE)),
            Case("j'ai pas très faim", listOf(APPETITE_LOSS)),
            Case("ça fait vraiment mal", listOf(PAIN)),
            // Not a symptom, or not this one
            Case("j'ai plus faim que d'habitude"),
            Case("j'ai sommeil"),
            Case("il fait étouffant"),
            Case("j'ai lessivé le sol"),
        ),
    )
  }

  @Test
  fun aSymptomNegatedOrSaidToBeOver_isNotDrafted() {
    check(
        Locale.ENGLISH,
        listOf(
            // A negation that reaches further than 3 words
            Case("I don't think I have a fever"),
            Case("I don't think so, I'm tired", listOf(FATIGUE)),
            // A negated list, with or without the recognizer's commas
            Case("no fever, nausea or pain"),
            Case("no fever nausea or pain"),
            Case("no fever, nausea or pain, just tired", listOf(FATIGUE)),
            // A comma before "or", and a symptom of several words in the list
            Case("no fever, nausea, or pain"),
            Case("no loss of appetite or nausea"),
            // A new clause isn't part of the list
            Case("no fever, but I have nausea", listOf(NAUSEA)),
            // Over
            Case("the nausea is gone"),
            Case("the pain's gone"),
            Case("the nausea really has gone"),
            Case("the nausea is completely gone, but I'm tired", listOf(FATIGUE)),
            Case("I had a headache this morning but it's gone now, I'm tired", listOf(FATIGUE)),
            // "are gone" is about all the symptoms said together, "is gone" only the last one
            Case("the nausea and pain are gone"),
            Case("the nausea is gone, but I still have pain", listOf(PAIN)),
            Case("I'm tired and the nausea is gone", listOf(FATIGUE)),
            // "has gone" right after a symptom only
            Case("headache since my husband has gone", listOf(HEADACHE)),
            Case("pain since the nurses have gone", listOf(PAIN)),
        ),
    )
    check(
        Locale.FRENCH,
        listOf(
            // "vraiment" doesn't count in the negation's reach
            Case("je n'ai pas vraiment eu de fièvre"),
            Case("je pense pas que j'aie de la fièvre"),
            Case("pas de fièvre, de nausées ni de douleur"),
            Case("pas de fièvre, de nausées, ni de douleur"),
            Case("la nausée est passée"),
            Case("les nausées et les douleurs sont passées"),
            Case("la fièvre est tombée, je suis fatiguée", listOf(FATIGUE)),
            Case("j'avais mal à la tête mais c'est passé"),
            Case("j'ai mal depuis que le médecin est passé", listOf(PAIN)),
        ),
    )
  }

  @Test
  fun anotherLanguage_isParsedAsEnglish() {
    check(Locale.GERMAN, listOf(Case("tired 4 out of 10", listOf(FATIGUE), 4)))
  }

  @Test
  fun notes_areTheTranscript_trimmedAndCutToTheLimit() {
    val long = "tired ".repeat(400)
    val cases =
        listOf(
            "  tired  " to "tired",
            long to long.trim().take(SymptomEntry.MAX_NOTES_LENGTH),
        )
    for ((transcript, notes) in cases) {
      assertEquals(notes, parseSpokenSymptom(transcript, Locale.ENGLISH, now).notes)
    }
  }

  private fun at(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)
}
