// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomType

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
 * @property determiners small words a list of symptoms may have before each one ("the", "de")
 * @property clauseBreaks words that end what a negation applies to ("but", "mais")
 * @property groupJoins words that join symptoms said together ("the nausea and pain")
 * @property endings phrases that say the symptom said just before them is over ("the nausea is
 *   gone")
 * @property pluralEndings phrases that say the symptoms said together just before them are over
 *   ("the nausea and pain are gone")
 * @property pronounEndings phrases that say the symptoms said before them are over, even in the
 *   clause before ("I had a headache but it's gone")
 * @property ignored phrases that look like a negation or a symptom but are neither
 * @property fillers words that may come between the words of a symptom or ignored phrase ("feel a
 *   bit sick", "pas très faim") without changing what it says
 * @property numbers numbers said as words; digits are always understood
 * @property outOf what joins an intensity and its scale ("7 out of 10")
 * @property strengthLinks what may come between a symptom and a strength said as a number alone
 *   ("nausea is about 6"); never "at", which says a time ("headache at 6")
 * @property strengthFor what says a strength is for the symptom said after it ("7 out of 10 for the
 *   nausea")
 * @property strengthEnds what may follow a strength said as a number alone ("nausée à 6, voilà"),
 *   besides the end, a clause break or another symptom; never a unit ("à 6 heures", "3 fois")
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
    val determiners: Set<String>,
    val clauseBreaks: Set<String>,
    val groupJoins: Set<String>,
    endings: List<String>,
    pluralEndings: List<String>,
    pronounEndings: List<String>,
    ignored: List<String>,
    fillers: List<String>,
    val numbers: Map<String, Int>,
    outOf: List<String>,
    strengthLinks: List<String>,
    strengthFor: List<String>,
    strengthEnds: List<String>,
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
  val pluralEndings: List<Phrase> = phrases(pluralEndings)
  val pronounEndings: List<Phrase> = phrases(pronounEndings)
  val fillers: List<Phrase> = phrases(fillers)
  val outOf: List<Phrase> = phrases(outOf)
  val strengthLinks: List<Phrase> = phrases(strengthLinks)
  val strengthFor: List<Phrase> = phrases(strengthFor)
  val strengthEnds: List<Phrase> = phrases(strengthEnds)
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

    /** [text] without its lines starting with `//`, so the phrase lists can explain themselves. */
    private fun withoutComments(text: String): String =
        text.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    /** The phrases in [text], separated by commas or new lines. */
    private fun phraseList(text: String): List<String> =
        withoutComments(text).split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The phrases of each symptom in [text]: a symptom's name and a colon ("FATIGUE: tired,
     * exhausted"), then its phrases, on as many lines as needed.
     */
    private fun symptomTable(text: String): Map<SymptomType, List<String>> =
        withoutComments(text)
            .split(Regex("(?=\\b[A-Z_]+:)"))
            .filter { it.isNotBlank() }
            .associate {
              SymptomType.valueOf(it.substringBefore(':').trim()) to
                  phraseList(it.substringAfter(':'))
            }

    private val ENGLISH =
        SpokenVocabulary(
            symptoms =
                symptomTable(
                    """
                FATIGUE: tired, exhausted, fatigue*, worn out, wiped out, exhaustion,
                  feel drained, feeling drained, felt drained, no energy, low energy, zero energy,
                  out of energy, run down, knackered
                NAUSEA: nause*, queasy, feel sick, feeling sick, felt sick, sick to my stomach,
                  throw up, threw up, throwing up, vomit*, retch*, dry heav*, puke*, puking
                PAIN: pain, pains, painful, hurt*, ache*, aching, achy, sore*, backache*,
                  stomachache*, toothache*, earache*, bellyache*, cramp*, throbbing
                FEVER: fever*, a temperature, high temperature
                // The recognizer sometimes writes "head ache" in two words
                HEADACHE: headache*, head ache*, migraine*, head hurts, my head hurts,
                  head is pounding, pounding head*, head is killing me, head's killing me,
                  head is splitting, splitting head*
                APPETITE_LOSS: no appetite, lost my appetite, lost appetite, appetite is gone,
                  appetite has gone, appetite's gone, appetite is low, appetite is poor,
                  loss of appetite, not hungry, poor appetite, don't want to eat,
                  don't feel like eating, didn't feel like eating
                SLEEP_PROBLEMS: can't sleep, couldn't sleep, didn't sleep, haven't slept,
                  hardly slept, barely slept, slept badly, slept poorly, trouble sleeping,
                  awake all night, keep waking up, kept waking up, tossing and turning, insomni*,
                  sleep problem*, sleeping problem*, problem* sleeping, sleep issue*,
                  sleep trouble*, difficulty sleeping, hard to sleep, struggl* to sleep,
                  can't fall asleep, couldn't fall asleep, trouble falling asleep,
                  can't get to sleep, couldn't get to sleep, woke up several times,
                  woke up many times, woke up a lot, restless night, sleepless, up all night
                BREATHLESSNESS: short of breath, shortness of breath, out of breath, breathless*,
                  // The recognizer sometimes writes "breath" for "breathe"
                  can't breath*, hard to breath*, difficult to breath*, struggl* to breath*,
                  trouble breathing, difficulty breathing, problem* breathing,
                  hard time breathing, breathing problem*, breathing difficult*,
                  can't catch my breath, couldn't catch my breath, can't get my breath, winded
                """
                ),
            // "t" is what is left of "n't" ("don't" → "don t")
            negations =
                phraseList("no, not, t, without, never, nor, neither, none, nothing").toSet(),
            clauseNegations = phraseList("don't think, do not think, don't believe"),
            listJoins = setOf("or", "nor"),
            determiners = setOf("the", "a", "an", "any", "my", "some"),
            clauseBreaks =
                phraseList("but, and, though, although, however, except, then, otherwise, just")
                    .toSet() + ",",
            groupJoins = setOf(",", "and"),
            // "went away" and "disappeared" could be either: only the last symptom is over
            endings =
                phraseList(
                    """
                is gone, has gone, 's gone, went away, has gone away, is over, has passed,
                has stopped, disappeared, has disappeared
                """
                ),
            pluralEndings =
                phraseList(
                    "are gone, have gone, are over, have passed, have stopped, have disappeared"
                ),
            pronounEndings =
                phraseList(
                    """
                it's gone, it is gone, it has gone, it went away, it's over, it is over,
                it has passed, it stopped, it has stopped
                """
                ),
            ignored =
                phraseList(
                    """
                don't know, not sure
                // Not a symptom: "tired of waiting", "pain-free", "a drain was drained"...
                tired of, sick and tired, feel sick and tired, feeling sick and tired, pain free,
                fever free, hurt my feelings, no appetite problem*, no appetite issue*,
                no appetite loss, no energy problem*
                // "can't stop throwing up" negates nothing
                can't stop, couldn't stop
                // A good night, said with a negation
                didn't sleep badly
                """
                ),
            fillers =
                phraseList(
                    """
                really, very, so, quite, too, pretty, extremely, totally, completely, even, all,
                a bit, a little, kind of
                """
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
            strengthLinks =
                phraseList(
                    """
                // "s" is what is left of "'s" ("nausea's about 6")
                is, s, was, about, around, roughly, approximately, maybe, like, a, at a, level
                // Where it hurts ("pain in my back 8")
                in, my, the, back, stomach, belly, head, leg, legs, arm, arms, knee, knees, foot,
                feet, neck, shoulder, shoulders, chest, throat, joints, muscles, hip, hips, hand,
                hands, tooth, teeth, ear, ears
                """
                ),
            strengthFor = listOf("for"),
            // A new sentence ("I", "it's") ends it too. Not "this morning": "headache 6 this
            // morning" could be a time
            strengthEnds =
                phraseList(
                    """
                ok, okay, now, today, right, thanks, thank you, i think, i guess, i, it, it's, my
                """
                ),
            articles = setOf("a", "an"),
            agoBefore = emptyList(),
            agoAfter = listOf("ago"),
            halfHour = listOf("half an hour"),
            minuteUnits = setOf("minute", "minutes", "min", "mins"),
            hourUnits = setOf("hour", "hours", "hr", "hrs"),
            daysAgo = mapOf("yesterday" to 1, "day before yesterday" to 2),
            otherDays =
                phraseList(
                    """
                tomorrow, monday, tuesday, wednesday, thursday, friday, saturday, sunday,
                next week, last week
                """
                ),
            clockWords = listOf("o'clock"),
            morning = listOf("am", "in the morning"),
            afternoon = listOf("pm", "in the afternoon", "in the evening", "at night"),
        )

    private val FRENCH =
        SpokenVocabulary(
            symptoms =
                symptomTable(
                    """
                FATIGUE: fatigu*, épuis*, suis crevé, suis crevée, sens crevé, sens crevée,
                  suis à plat, sens à plat, pas d'énergie, n'ai plus d'énergie, aucune énergie,
                  sans énergie, suis naze, sens naze, suis lessivé*, sens lessivé*
                NAUSEA: nausé*, envie de vomir, vomi*, mal au cœur, barbouill*, haut-le-cœur,
                  tout rendu, envie de gerber
                // "mal" alone isn't pain: "je me sens mal", "pas mal". "j'ai mal" is, unless it
                // starts a longer phrase ("j'ai mal dormi")
                PAIN: douleur*, ai mal, mal au, mal aux, mal à la, mal à l', mal partout, fait mal,
                  mal de dos, mal de ventre, mal de gorge, maux de ventre, douloureu*, crampe*,
                  courbatu*, élancement*
                FEVER: fièvre*, de la température
                HEADACHE: mal à la tête, mal de tête, maux de tête, mal au crâne, mal de crâne,
                  fait mal à la tête, fait mal au crâne, migraine*, céphalée*
                APPETITE_LOSS: pas faim, plus faim, pas d'appétit, n'ai plus d'appétit,
                  aucun appétit, perte d'appétit, manque d'appétit, pas envie de manger,
                  plus envie de manger, aucune envie de manger, perdu l'appétit, peu d'appétit,
                  appétit coupé, coupé l'appétit
                SLEEP_PROBLEMS: mal dormi, pas dormi, peu dormi, pas pu dormir, mal à dormir,
                  peux pas dormir, arrive pas à dormir, insomnie*, mauvaise nuit, nuit blanche,
                  dors mal, pas fermé l'œil, trouble* du sommeil, problème* de sommeil,
                  problème* du sommeil, mal à m'endormir, pas à m'endormir, pas pu m'endormir,
                  réveillé* plusieurs fois, réveillé* toute la nuit, me réveille la nuit,
                  sommeil agité, nuit agitée
                BREATHLESSNESS: essouffl*, souffle court, manque de souffle, mal à respirer,
                  respire mal, manque d'air, peine à respirer,
                  // Not "étouff*": "il fait étouffant" is the weather
                  j'étouffe, sens étouffé*, suffoqu*, difficulté* à respirer,
                  difficulté* respiratoire*, difficile de respirer, dur de respirer,
                  respire difficilement, respirer difficilement, bout de souffle, souffle coupé
                """
                ),
            negations = phraseList("pas, sans, aucun, aucune, jamais, ni, rien").toSet(),
            negationsAfterNe = setOf("plus"),
            clauseNegations = phraseList("crois pas, pense pas"),
            listJoins = setOf("ou", "ni"),
            // After spokenWords, "d'" and "l'" are "d" and "l"
            determiners = setOf("de", "des", "du", "d", "la", "le", "les", "l", "mes", "ma", "mon"),
            clauseBreaks =
                phraseList("mais, et, cependant, pourtant, sauf, puis, sinon, juste").toSet() + ",",
            groupJoins = setOf(",", "et"),
            endings =
                phraseList(
                    "est passé*, est parti*, a disparu, est tombé*, s'est arrêté*, est fini*"
                ),
            pluralEndings =
                phraseList(
                    """
                sont passé*, sont parti*, ont disparu, sont tombé*, se sont arrêté*, sont fini*
                """
                ),
            pronounEndings =
                phraseList("c'est passé, c'est parti, c'est fini, ça a disparu, ça s'est arrêté"),
            ignored =
                phraseList(
                    """
                // "pas mal de" is "quite a lot of"; "je ne sais pas" negates nothing
                pas mal de, sais pas
                // Not a symptom: out of stock, uneasy, "I struggle to", a nap, it saddens me
                est épuisé*, sont épuisé*, mal à l'aise, du mal à la, du mal à l', sen* mal au,
                sen* mal aux, sen* mal à la, sen* mal à l', un peu dormi, fait mal au cœur
                // Hungrier than usual
                plus faim que
                """
                ),
            fillers =
                phraseList(
                    "très, vraiment, trop, complètement, tellement, super, si, assez, encore, un peu"
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
            // "à 6" says a strength, but "à 6 heures" a time: a strength ends what is said of it
            strengthLinks =
                phraseList(
                    """
                est, était, à, de, environ, à peu près, autour de, genre, peut-être
                // Where it hurts ("mal au dos à 9")
                dos, ventre, bras, jambe, jambes, genou, genoux, pied, pieds, cou, nuque, épaule,
                épaules, poitrine, gorge, estomac, hanche, hanches, main, mains, dent, dents,
                oreille, oreilles, articulations, muscles
                """
                ),
            strengthFor = listOf("pour"),
            // A new sentence ("j'ai", "c'est") ends it too. Not "ce matin": "mal à la tête à 6 ce
            // matin" is a time
            strengthEnds =
                phraseList(
                    """
                ok, d'accord, voilà, quoi, hein, bon, merci, maintenant, je pense, je crois, j', je,
                c'est, ça, il, elle
                """
                ),
            articles = emptySet(),
            agoBefore = listOf("il y a"),
            agoAfter = emptyList(),
            halfHour = listOf("une demi-heure", "une demie heure"),
            minuteUnits = setOf("minute", "minutes", "min"),
            hourUnits = setOf("heure", "heures", "h"),
            daysAgo = mapOf("hier" to 1, "avant-hier" to 2),
            otherDays =
                phraseList(
                    """
                demain, lundi, mardi, mercredi, jeudi, vendredi, samedi, dimanche,
                semaine prochaine, semaine dernière
                """
                ),
            clockWords = listOf("h", "heure", "heures"),
            morning = listOf("du matin"),
            afternoon = listOf("de l'après-midi", "du soir"),
        )
  }
}
