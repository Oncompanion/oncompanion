// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.APPETITE_LOSS
import com.github.se.oncompanion.model.symptom.SymptomType.BREATHLESSNESS
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.FEVER
import com.github.se.oncompanion.model.symptom.SymptomType.HEADACHE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.symptom.SymptomType.PAIN
import com.github.se.oncompanion.model.symptom.SymptomType.SLEEP_PROBLEMS

/** A phrase as [spokenWords]; a word ending in `*` matches any word starting with the rest. */
internal typealias Phrase = List<String>

/**
 * The words [parseSpokenSymptom] understands in one language. Phrases are written as spoken and
 * compared after [spokenWords], so accents, case and apostrophes don't matter.
 *
 * @property symptoms phrases for each symptom; [SymptomType.OTHER] is never drafted
 * @property negations words that negate the symptom said soon after them, in the same clause
 * @property negationsAfterNe words that only negate after "ne" ("je n'ai plus de fièvre"), as
 *   "plus" alone also means "more"
 * @property clauseNegations phrases that negate every symptom up to the end of their clause ("I
 *   don't think I have a fever")
 * @property listJoins words that join the last item of a list ("no fever, nausea or pain"): a
 *   negation goes on through the list
 * @property clauseBreaks words that end what a negation applies to ("but", "mais")
 * @property endings phrases that say the symptom said just before them is over ("the nausea is
 *   gone")
 * @property pronounEndings phrases that say the symptoms said before them are over, even in the
 *   clause before ("I had a headache but it's gone")
 * @property ignored phrases that look like a negation or a symptom but are neither
 * @property fillers words that may come between the words of a symptom or ignored phrase ("feel a
 *   bit sick", "pas très faim") without changing what it says
 * @property numbers numbers said as words; digits are always understood
 * @property outOf what joins an intensity and its scale ("7 out of 10")
 * @property articles words that mean "one" before a duration ("an hour ago")
 * @property agoBefore what comes before a duration in the past ("il y a 2 heures")
 * @property agoAfter what comes after a duration in the past ("2 hours ago")
 * @property halfHour half an hour, as one phrase
 * @property minuteUnits, hourUnits the units of a duration
 * @property daysAgo days said by name, with how many days ago they are
 * @property otherDays days the parser can't place ("tomorrow", "lundi"): when one is said, the day
 *   and time stay empty rather than default to today
 * @property clockWords what makes a number a time of day ("9 o'clock", "9 heures")
 * @property morning, afternoon what says whether a time is before or after noon
 */
internal class SpokenVocabulary(
    symptoms: Map<SymptomType, List<String>>,
    val negations: Set<String>,
    val negationsAfterNe: Set<String> = emptySet(),
    clauseNegations: List<String>,
    val listJoins: Set<String>,
    val clauseBreaks: Set<String>,
    endings: List<String>,
    pronounEndings: List<String>,
    ignored: List<String>,
    fillers: List<String>,
    val numbers: Map<String, Int>,
    outOf: List<String>,
    val articles: Set<String>,
    agoBefore: List<String>,
    agoAfter: List<String>,
    halfHour: List<String>,
    val minuteUnits: Set<String>,
    val hourUnits: Set<String>,
    daysAgo: Map<String, Long>,
    otherDays: List<String>,
    clockWords: List<String>,
    morning: List<String>,
    afternoon: List<String>,
) {
  val symptoms: List<Pair<Phrase, SymptomType>> = symptoms.flatMap { (type, phrases) ->
    phrases.map { spokenWords(it) to type }
  }
  val ignored: List<Phrase> = phrases(ignored)
  val clauseNegations: List<Phrase> = phrases(clauseNegations)
  val endings: List<Phrase> = phrases(endings)
  val pronounEndings: List<Phrase> = phrases(pronounEndings)
  val fillers: List<Phrase> = phrases(fillers)
  val outOf: List<Phrase> = phrases(outOf)
  val agoBefore: List<Phrase> = phrases(agoBefore)
  val agoAfter: List<Phrase> = phrases(agoAfter)
  val halfHour: List<Phrase> = phrases(halfHour)
  /** Longest first, so "avant-hier" isn't read as "hier". */
  val daysAgo: List<Pair<Phrase, Long>> =
      daysAgo
          .map { (phrase, days) -> spokenWords(phrase) to days }
          .sortedByDescending { it.first.size }
  val otherDays: List<Phrase> = phrases(otherDays)
  val clockWords: List<Phrase> = phrases(clockWords)
  val morning: List<Phrase> = phrases(morning)
  val afternoon: List<Phrase> = phrases(afternoon)

  private fun phrases(list: List<String>): List<Phrase> = list.map(::spokenWords)

  companion object {
    /** The vocabulary for [language] (an ISO 639 code): French for "fr", English otherwise. */
    fun of(language: String): SpokenVocabulary = if (language == "fr") FRENCH else ENGLISH

    private val ENGLISH =
        SpokenVocabulary(
            symptoms =
                mapOf(
                    FATIGUE to
                        listOf(
                            "tired",
                            "exhausted",
                            "fatigue*",
                            "worn out",
                            "wiped out",
                            "exhaustion",
                            "feel drained",
                            "feeling drained",
                            "felt drained",
                            "no energy",
                            "low energy",
                            "zero energy",
                            "out of energy",
                            "run down",
                            "knackered",
                        ),
                    NAUSEA to
                        listOf(
                            "nause*",
                            "queasy",
                            "feel sick",
                            "feeling sick",
                            "felt sick",
                            "sick to my stomach",
                            "throw up",
                            "threw up",
                            "throwing up",
                            "vomit*",
                            "retch*",
                            "dry heav*",
                            "puke*",
                            "puking",
                        ),
                    PAIN to
                        listOf(
                            "pain",
                            "pains",
                            "painful",
                            "hurt*",
                            "ache*",
                            "aching",
                            "achy",
                            "sore*",
                            "backache*",
                            "stomachache*",
                            "toothache*",
                            "earache*",
                            "bellyache*",
                            "cramp*",
                            "throbbing",
                        ),
                    FEVER to listOf("fever*", "a temperature", "high temperature"),
                    // The recognizer sometimes writes "head ache" in two words
                    HEADACHE to
                        listOf(
                            "headache*",
                            "head ache*",
                            "migraine*",
                            "head hurts",
                            "my head hurts",
                            "head is pounding",
                            "pounding head*",
                            "head is killing me",
                            "head's killing me",
                            "head is splitting",
                            "splitting head*",
                        ),
                    APPETITE_LOSS to
                        listOf(
                            "no appetite",
                            "lost my appetite",
                            "lost appetite",
                            "appetite is gone",
                            "appetite has gone",
                            "appetite's gone",
                            "appetite is low",
                            "appetite is poor",
                            "loss of appetite",
                            "not hungry",
                            "poor appetite",
                            "don't want to eat",
                            "don't feel like eating",
                            "didn't feel like eating",
                        ),
                    SLEEP_PROBLEMS to
                        listOf(
                            "can't sleep",
                            "couldn't sleep",
                            "didn't sleep",
                            "haven't slept",
                            "hardly slept",
                            "barely slept",
                            "slept badly",
                            "slept poorly",
                            "trouble sleeping",
                            "awake all night",
                            "keep waking up",
                            "kept waking up",
                            "tossing and turning",
                            "insomni*",
                            "sleep problem*",
                            "sleeping problem*",
                            "problem* sleeping",
                            "sleep issue*",
                            "sleep trouble*",
                            "difficulty sleeping",
                            "hard to sleep",
                            "struggl* to sleep",
                            "can't fall asleep",
                            "couldn't fall asleep",
                            "trouble falling asleep",
                            "can't get to sleep",
                            "couldn't get to sleep",
                            "woke up several times",
                            "woke up many times",
                            "woke up a lot",
                            "restless night",
                            "sleepless",
                            "up all night",
                        ),
                    BREATHLESSNESS to
                        listOf(
                            "short of breath",
                            "shortness of breath",
                            "out of breath",
                            "breathless*",
                            // The recognizer sometimes writes "breath" for "breathe"
                            "can't breath*",
                            "hard to breath*",
                            "difficult to breath*",
                            "struggl* to breath*",
                            "trouble breathing",
                            "difficulty breathing",
                            "problem* breathing",
                            "hard time breathing",
                            "breathing problem*",
                            "breathing difficult*",
                            "can't catch my breath",
                            "couldn't catch my breath",
                            "can't get my breath",
                            "winded",
                        ),
                ),
            // "t" is what is left of "n't" ("don't" → "don t")
            negations =
                setOf("no", "not", "t", "without", "never", "nor", "neither", "none", "nothing"),
            clauseNegations = listOf("don't think", "do not think", "don't believe"),
            listJoins = setOf("or", "nor"),
            clauseBreaks =
                setOf(
                    ",",
                    "but",
                    "and",
                    "though",
                    "although",
                    "however",
                    "except",
                    "then",
                    "otherwise",
                    "just",
                ),
            endings =
                listOf(
                    "is gone",
                    "are gone",
                    "has gone",
                    "have gone",
                    "'s gone",
                    "went away",
                    "has gone away",
                    "is over",
                    "has passed",
                    "have passed",
                    "has stopped",
                    "have stopped",
                    "disappeared",
                    "has disappeared",
                ),
            pronounEndings =
                listOf(
                    "it's gone",
                    "it is gone",
                    "it has gone",
                    "it went away",
                    "it's over",
                    "it is over",
                    "it has passed",
                    "it stopped",
                    "it has stopped",
                ),
            ignored =
                listOf(
                    "don't know",
                    "not sure",
                    // Not a symptom: "tired of waiting", "pain-free", "a drain was drained"...
                    "tired of",
                    "sick and tired",
                    "feel sick and tired",
                    "feeling sick and tired",
                    "pain free",
                    "fever free",
                    "hurt my feelings",
                    "no appetite problem*",
                    "no appetite issue*",
                    "no appetite loss",
                    "no energy problem*",
                    // "can't stop throwing up" negates nothing
                    "can't stop",
                    "couldn't stop",
                    // A good night, said with a negation
                    "didn't sleep badly",
                ),
            fillers =
                listOf(
                    "really",
                    "very",
                    "so",
                    "quite",
                    "too",
                    "pretty",
                    "extremely",
                    "totally",
                    "completely",
                    "even",
                    "all",
                    "a bit",
                    "a little",
                    "kind of",
                ),
            numbers =
                mapOf(
                    "zero" to 0,
                    "one" to 1,
                    "two" to 2,
                    "three" to 3,
                    "four" to 4,
                    "five" to 5,
                    "six" to 6,
                    "seven" to 7,
                    "eight" to 8,
                    "nine" to 9,
                    "ten" to 10,
                ),
            outOf = listOf("out of", "on", "over", "/"),
            articles = setOf("a", "an"),
            agoBefore = emptyList(),
            agoAfter = listOf("ago"),
            halfHour = listOf("half an hour"),
            minuteUnits = setOf("minute", "minutes", "min", "mins"),
            hourUnits = setOf("hour", "hours", "hr", "hrs"),
            daysAgo = mapOf("yesterday" to 1, "day before yesterday" to 2),
            otherDays =
                listOf(
                    "tomorrow",
                    "monday",
                    "tuesday",
                    "wednesday",
                    "thursday",
                    "friday",
                    "saturday",
                    "sunday",
                    "next week",
                    "last week",
                ),
            clockWords = listOf("o'clock"),
            morning = listOf("am", "in the morning"),
            afternoon = listOf("pm", "in the afternoon", "in the evening", "at night"),
        )

    private val FRENCH =
        SpokenVocabulary(
            symptoms =
                mapOf(
                    FATIGUE to
                        listOf(
                            "fatigu*",
                            "épuis*",
                            "suis crevé",
                            "suis crevée",
                            "sens crevé",
                            "sens crevée",
                            "suis à plat",
                            "sens à plat",
                            "pas d'énergie",
                            "n'ai plus d'énergie",
                            "aucune énergie",
                            "sans énergie",
                            "suis naze",
                            "sens naze",
                            "suis lessivé*",
                            "sens lessivé*",
                        ),
                    NAUSEA to
                        listOf(
                            "nausé*",
                            "envie de vomir",
                            "vomi*",
                            "mal au cœur",
                            "barbouill*",
                            "haut-le-cœur",
                            "tout rendu",
                            "envie de gerber",
                        ),
                    // "mal" alone isn't pain: "je me sens mal", "pas mal". "j'ai mal" is, unless
                    // it starts a longer phrase ("j'ai mal dormi")
                    PAIN to
                        listOf(
                            "douleur*",
                            "ai mal",
                            "mal au",
                            "mal aux",
                            "mal à la",
                            "mal à l'",
                            "mal partout",
                            "fait mal",
                            "mal de dos",
                            "mal de ventre",
                            "mal de gorge",
                            "maux de ventre",
                            "douloureu*",
                            "crampe*",
                            "courbatu*",
                            "élancement*",
                        ),
                    FEVER to listOf("fièvre*", "de la température"),
                    HEADACHE to
                        listOf(
                            "mal à la tête",
                            "mal de tête",
                            "maux de tête",
                            "mal au crâne",
                            "mal de crâne",
                            "fait mal à la tête",
                            "fait mal au crâne",
                            "migraine*",
                            "céphalée*",
                        ),
                    APPETITE_LOSS to
                        listOf(
                            "pas faim",
                            "plus faim",
                            "pas d'appétit",
                            "n'ai plus d'appétit",
                            "aucun appétit",
                            "perte d'appétit",
                            "manque d'appétit",
                            "pas envie de manger",
                            "plus envie de manger",
                            "aucune envie de manger",
                            "perdu l'appétit",
                            "peu d'appétit",
                            "appétit coupé",
                            "coupé l'appétit",
                        ),
                    SLEEP_PROBLEMS to
                        listOf(
                            "mal dormi",
                            "pas dormi",
                            "peu dormi",
                            "pas pu dormir",
                            "mal à dormir",
                            "peux pas dormir",
                            "arrive pas à dormir",
                            "insomnie*",
                            "mauvaise nuit",
                            "nuit blanche",
                            "dors mal",
                            "pas fermé l'œil",
                            "trouble* du sommeil",
                            "problème* de sommeil",
                            "problème* du sommeil",
                            "mal à m'endormir",
                            "pas à m'endormir",
                            "pas pu m'endormir",
                            "réveillé* plusieurs fois",
                            "réveillé* toute la nuit",
                            "me réveille la nuit",
                            "sommeil agité",
                            "nuit agitée",
                        ),
                    BREATHLESSNESS to
                        listOf(
                            "essouffl*",
                            "souffle court",
                            "manque de souffle",
                            "mal à respirer",
                            "respire mal",
                            "manque d'air",
                            "peine à respirer",
                            // Not "étouff*": "il fait étouffant" is the weather
                            "j'étouffe",
                            "sens étouffé*",
                            "suffoqu*",
                            "difficulté* à respirer",
                            "difficulté* respiratoire*",
                            "difficile de respirer",
                            "dur de respirer",
                            "respire difficilement",
                            "respirer difficilement",
                            "bout de souffle",
                            "souffle coupé",
                        ),
                ),
            negations = setOf("pas", "sans", "aucun", "aucune", "jamais", "ni", "rien"),
            negationsAfterNe = setOf("plus"),
            clauseNegations = listOf("crois pas", "pense pas"),
            listJoins = setOf("ou", "ni"),
            clauseBreaks =
                setOf(",", "mais", "et", "cependant", "pourtant", "sauf", "puis", "sinon", "juste"),
            endings =
                listOf(
                    "est passé*",
                    "sont passé*",
                    "est parti*",
                    "sont parti*",
                    "a disparu",
                    "ont disparu",
                    "est tombé*",
                    "s'est arrêté*",
                    "est fini*",
                ),
            pronounEndings =
                listOf(
                    "c'est passé",
                    "c'est parti",
                    "c'est fini",
                    "ça a disparu",
                    "ça s'est arrêté",
                ),
            ignored =
                listOf(
                    // "pas mal de" is "quite a lot of"; "je ne sais pas" negates nothing
                    "pas mal de",
                    "sais pas",
                    // Not a symptom: out of stock, uneasy, "I struggle to", a nap, it saddens me
                    "est épuisé*",
                    "sont épuisé*",
                    "mal à l'aise",
                    "du mal à la",
                    "du mal à l'",
                    "sen* mal au",
                    "sen* mal aux",
                    "sen* mal à la",
                    "sen* mal à l'",
                    "un peu dormi",
                    "fait mal au cœur",
                    // Hungrier than usual
                    "plus faim que",
                ),
            fillers =
                listOf(
                    "très",
                    "vraiment",
                    "trop",
                    "complètement",
                    "tellement",
                    "super",
                    "si",
                    "assez",
                    "encore",
                    "un peu",
                ),
            numbers =
                mapOf(
                    "zéro" to 0,
                    "un" to 1,
                    "une" to 1,
                    "deux" to 2,
                    "trois" to 3,
                    "quatre" to 4,
                    "cinq" to 5,
                    "six" to 6,
                    "sept" to 7,
                    "huit" to 8,
                    "neuf" to 9,
                    "dix" to 10,
                ),
            outOf = listOf("sur", "/"),
            articles = emptySet(),
            agoBefore = listOf("il y a"),
            agoAfter = emptyList(),
            halfHour = listOf("une demi-heure", "une demie heure"),
            minuteUnits = setOf("minute", "minutes", "min"),
            hourUnits = setOf("heure", "heures", "h"),
            daysAgo = mapOf("hier" to 1, "avant-hier" to 2),
            otherDays =
                listOf(
                    "demain",
                    "lundi",
                    "mardi",
                    "mercredi",
                    "jeudi",
                    "vendredi",
                    "samedi",
                    "dimanche",
                    "semaine prochaine",
                    "semaine dernière",
                ),
            clockWords = listOf("h", "heure", "heures"),
            morning = listOf("du matin"),
            afternoon = listOf("de l'après-midi", "du soir"),
        )
  }
}
