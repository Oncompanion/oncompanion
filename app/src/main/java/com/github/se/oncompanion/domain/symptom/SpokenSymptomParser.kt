// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Drafts the add symptom form from what the patient said ([transcript], in [locale]'s language:
 * French or English), at [now]. Only what was said plainly is filled in: the symptoms named and not
 * negated, an intensity said on a scale of 10, and when it was felt. Anything unclear or
 * contradictory stays empty, and nothing is concluded from what was said: the app organizes, it
 * doesn't interpret (see AGENTS.md). The whole transcript goes into the notes.
 */
fun parseSpokenSymptom(transcript: String, locale: Locale, now: ZonedDateTime): SymptomDraft {
  val words = spokenWords(transcript)
  val vocabulary = SpokenVocabulary.of(locale.language)
  val (date, time) = SpokenWhen(words, vocabulary, now).resolve()
  return SymptomDraft(
      types = SpokenSymptoms(words, vocabulary).read(),
      intensity = intensitySaid(words, vocabulary),
      date = date,
      time = time,
      notes = transcript.trim().take(SymptomEntry.MAX_NOTES_LENGTH),
  )
}

/**
 * [text] as words to compare with a [SpokenVocabulary]: lowercase, without accents or apostrophes,
 * with "3:30" as "3h30", "p.m." as "pm", and punctuation that ends a clause kept as ",". The
 * recognizer adds punctuation on some phones and not on others, so it is never required.
 */
internal fun spokenWords(text: String): List<String> {
  val marked =
      text
          .lowercase(Locale.ROOT)
          .replace("œ", "oe")
          .replace(Regex("\\b([ap])\\.\\s?m\\b\\.?"), "$1m")
          .replace(Regex("(\\d)(am|pm)\\b"), "$1 $2")
          .replace(Regex("(\\d{1,2})[:.](\\d{2})"), "$1h$2")
  return Normalizer.normalize(marked, Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,;:!?]+"), " , ")
      .replace("/", " / ")
      .replace(Regex("[^a-z0-9/,*]+"), " ")
      .split(' ')
      .filter { it.isNotEmpty() }
}

/** Whether [said] is the phrase word [word]: equal, or starting with it if it ends with `*`. */
private fun isWord(word: String, said: String?): Boolean =
    said != null && if (word.endsWith('*')) said.startsWith(word.dropLast(1)) else said == word

/**
 * How many words of [phrase] match [words] from [start], or 0 if it doesn't match there. Any of
 * [fillers] may come between two words of the phrase ("feel a bit sick" for "feel sick").
 */
private fun matchAt(
    words: List<String>,
    start: Int,
    phrase: Phrase,
    fillers: List<Phrase> = emptyList(),
): Int {
  var at = start
  for ((k, word) in phrase.withIndex()) {
    // The phrase's own word first, so a filler that is also part of the phrase stays in it
    if (k > 0 && !isWord(word, words.getOrNull(at))) at += fillersAt(words, at, fillers)
    if (!isWord(word, words.getOrNull(at))) return 0
    at++
  }
  return at - start
}

/** How many words of [fillers] follow each other in [words] from [start]. */
private fun fillersAt(words: List<String>, start: Int, fillers: List<Phrase>): Int {
  var at = start
  while (true) {
    val length = anyAt(words, at, fillers)
    if (length == 0) return at - start
    at += length
  }
}

/** The longest of [phrases] that matches [words] at [start], with its length. */
private fun <T> longestAt(
    words: List<String>,
    start: Int,
    phrases: List<Pair<Phrase, T>>,
    fillers: List<Phrase> = emptyList(),
) =
    phrases
        .map { (phrase, value) -> matchAt(words, start, phrase, fillers) to value }
        .filter { it.first > 0 }
        .maxByOrNull { it.first }

/** How many words of any of [phrases] match at [start], or 0. */
private fun anyAt(
    words: List<String>,
    start: Int,
    phrases: List<Phrase>,
    fillers: List<Phrase> = emptyList(),
): Int = phrases.maxOfOrNull { matchAt(words, start, it, fillers) } ?: 0

/**
 * The symptom said at [start] and how many words it takes, or `null`. A symptom that starts inside
 * it and ends after it wins: in "j'ai mal dormi", "mal dormi" is what was said, not "ai mal".
 */
private fun symptomAt(
    words: List<String>,
    start: Int,
    vocabulary: SpokenVocabulary,
): Pair<Int, SymptomType>? {
  val found = longestAt(words, start, vocabulary.symptoms, vocabulary.fillers) ?: return null
  val end = start + found.first
  val overtaken =
      (start + 1 until end).any { later ->
        val other = longestAt(words, later, vocabulary.symptoms, vocabulary.fillers)
        other != null && later + other.first > end
      }
  return found.takeUnless { overtaken }
}

/**
 * A negation reaches a symptom said at most this many words after it ("no nausea or headache"), not
 * counting [SpokenVocabulary.fillers] ("je n'ai pas vraiment eu de fièvre").
 */
private const val NEGATION_REACH = 3

/** Where the word just read stands to the symptoms said together before it. */
private enum class GroupState {
  /** Not right after symptoms: "is gone" says nothing of them. */
  NONE,
  /** Right after a symptom ("the nausea"). */
  AFTER_SYMPTOM,
  /** After a symptom and a word joining another one to it ("the nausea and"). */
  AFTER_JOIN,
}

/**
 * The symptoms in [words], in the order they were first said, without those negated or said to be
 * over. A symptom both said and negated ("headache this morning, no headache now") is left out: not
 * guessed.
 */
private class SpokenSymptoms(
    private val words: List<String>,
    private val vocabulary: SpokenVocabulary,
) {
  private val said = mutableListOf<SymptomType>()
  private val negated = mutableSetOf<SymptomType>()
  /** How many more words the last negation reaches. */
  private var reach = 0
  /** Whether a negation reaches the end of the clause ("I don't think I have a fever"). */
  private var clauseNegated = false
  /** The symptoms said in the current and previous clauses, which "it's gone" says are over. */
  private var clause = mutableListOf<SymptomType>()
  private var previousClause = listOf<SymptomType>()
  /** The symptoms said together last ("the nausea and pain"), which "are gone" says are over. */
  private var group = mutableListOf<SymptomType>()
  private var groupState = GroupState.NONE

  fun read(): List<SymptomType> {
    var i = 0
    while (i < words.size) {
      val state = groupState
      groupState = GroupState.NONE
      i +=
          readBreak(i, state).takeIf { it > 0 }
              ?: readIgnored(i).takeIf { it > 0 }
              ?: readClauseNegation(i).takeIf { it > 0 }
              ?: readEnding(i, state).takeIf { it > 0 }
              ?: readSymptom(i, state).takeIf { it > 0 }
              ?: readFiller(i, state).takeIf { it > 0 }
              ?: readWord(i, state)
    }
    return said.distinct().filter { it !in negated }
  }

  private fun negationReaches() = reach > 0 || clauseNegated

  /** A word joining a symptom to the ones before it keeps them together. */
  private fun joined(state: GroupState) =
      if (state == GroupState.NONE) GroupState.NONE else GroupState.AFTER_JOIN

  /** "," or "but": ends the clause, unless a negated list goes on ("no fever, nausea or pain"). */
  private fun readBreak(i: Int, state: GroupState): Int {
    if (words[i] !in vocabulary.clauseBreaks) return 0
    if (words[i] in vocabulary.groupJoins) groupState = joined(state)
    if (words[i] == "," && negationReaches() && listGoesOn(i + 1)) {
      reach = NEGATION_REACH
    } else {
      reach = 0
      clauseNegated = false
      if (clause.isNotEmpty()) previousClause = clause
      clause = mutableListOf()
    }
    return 1
  }

  /**
   * Whether a list goes on from [start] to "or" or "ni" ("nausea, or pain", "de nausées, ni"),
   * through nothing but symptoms, commas, [SpokenVocabulary.determiners] and fillers. Any other
   * word ends it: in "no fever, but I have nausea", the nausea isn't negated.
   */
  private fun listGoesOn(start: Int): Boolean {
    var at = start
    while (at < words.size) {
      val word = words[at]
      if (word in vocabulary.listJoins) return true
      val skip =
          symptomAt(words, at, vocabulary)?.first
              ?: if (word == "," || word in vocabulary.determiners) 1
              else anyAt(words, at, vocabulary.fillers)
      if (skip == 0) return false
      at += skip
    }
    return false
  }

  private fun readIgnored(i: Int): Int =
      anyAt(words, i, vocabulary.ignored, vocabulary.fillers).also { reach = maxOf(0, reach - it) }

  private fun readClauseNegation(i: Int): Int =
      anyAt(words, i, vocabulary.clauseNegations).also { if (it > 0) clauseNegated = true }

  /**
   * "it's gone" says the symptoms of its clause are over, or else those of the clause before ("I
   * had a headache but it's gone"). Right after symptoms, "are gone" says all those said together
   * are over, and "is gone" only the last one: "le médecin est passé" says nothing of a pain said
   * earlier, and "I'm tired and the nausea is gone" nothing of the fatigue.
   */
  private fun readEnding(i: Int, state: GroupState): Int {
    val pronounEnding = anyAt(words, i, vocabulary.pronounEndings, vocabulary.fillers)
    if (pronounEnding > 0) {
      negated += clause.ifEmpty { previousClause }
      return pronounEnding
    }
    val after = state == GroupState.AFTER_SYMPTOM
    val pluralEnding = anyAt(words, i, vocabulary.pluralEndings, vocabulary.fillers)
    if (pluralEnding > 0) {
      if (after) negated += group
      return pluralEnding
    }
    return anyAt(words, i, vocabulary.endings, vocabulary.fillers).also {
      if (it > 0 && after) negated += group.last()
    }
  }

  private fun readSymptom(i: Int, state: GroupState): Int {
    val (length, type) = symptomAt(words, i, vocabulary) ?: return 0
    // "pas faim" or "can't sleep" is the symptom itself, not its negation
    val negatesItself = words.subList(i, i + length).any { isNegation(it, vocabulary) }
    val isNegated = !negatesItself && negationReaches()
    if (isNegated) negated += type else said += type
    clause += type
    if (state == GroupState.AFTER_JOIN) group += type else group = mutableListOf(type)
    groupState = GroupState.AFTER_SYMPTOM
    // A negated list reaches as far again after each of its symptoms ("no loss of appetite or
    // nausea"), however many words the symptom takes
    reach = if (isNegated && listGoesOn(i + length)) NEGATION_REACH else maxOf(0, reach - length)
    return length
  }

  /** "really", "vraiment": counts for neither a negation's reach nor "is gone". */
  private fun readFiller(i: Int, state: GroupState): Int =
      anyAt(words, i, vocabulary.fillers).also { if (it > 0) groupState = state }

  /**
   * A negation, "or" in a negated list, "the" between symptoms said together, or any other word.
   */
  private fun readWord(i: Int, state: GroupState): Int {
    val word = words[i]
    if (word in vocabulary.determiners) groupState = joined(state)
    val negates =
        isNegation(word, vocabulary) &&
            (word !in vocabulary.negationsAfterNe ||
                words.subList(maxOf(0, i - 2), i).any { it == "n" || it == "ne" })
    reach =
        when {
          negates -> NEGATION_REACH
          // "no nausea or headache": the list goes on as far again
          word in vocabulary.listJoins && reach > 0 -> NEGATION_REACH
          else -> maxOf(0, reach - 1)
        }
    return 1
  }
}

private fun isNegation(word: String, vocabulary: SpokenVocabulary) =
    word in vocabulary.negations || word in vocabulary.negationsAfterNe

/** A number said in digits or in words, or `null`. */
private fun numberAt(words: List<String>, i: Int, vocabulary: SpokenVocabulary): Int? =
    words.getOrNull(i)?.let { it.toIntOrNull() ?: vocabulary.numbers[it] }

/**
 * The intensity said as "N out of 10" (or "N/10", "N sur dix"), if it is the only one said and from
 * [SymptomEntry.MIN_INTENSITY] to [SymptomEntry.MAX_INTENSITY]. A number alone is never an
 * intensity: it could be a time or a temperature.
 */
private fun intensitySaid(words: List<String>, vocabulary: SpokenVocabulary): Int? {
  val said =
      words.indices.mapNotNull { i ->
        val value = numberAt(words, i, vocabulary) ?: return@mapNotNull null
        val outOf = anyAt(words, i + 1, vocabulary.outOf)
        value.takeIf { outOf > 0 && numberAt(words, i + 1 + outOf, vocabulary) == 10 }
      }
  return said.distinct().singleOrNull()?.takeIf {
    it in SymptomEntry.MIN_INTENSITY..SymptomEntry.MAX_INTENSITY
  }
}

/**
 * When the symptom was felt, from [words] said at [now]: a duration ago ("2 hours ago"), a day
 * ("yesterday"), a time of day ("at 9 am", "à 15h"), or a day and a time. Vague moments ("this
 * morning") are left empty, as are contradicting ones, times that haven't come yet and any other
 * day said ("tomorrow", "lundi"), which a time alone would otherwise place on today.
 */
private class SpokenWhen(
    private val words: List<String>,
    private val vocabulary: SpokenVocabulary,
    private val now: ZonedDateTime,
) {
  private val durationsAgo = mutableListOf<Long>()
  private val daysAgo = mutableListOf<Long>()
  /** The hours each time of day said could be, and its minutes. */
  private val clockTimes = mutableListOf<Pair<List<Int>, Int>>()
  /** Whether a day other than today or [SpokenVocabulary.daysAgo] was said. */
  private var otherDay = false

  /** The day and time said, each `null` if not said or unclear. */
  fun resolve(): Pair<LocalDate?, LocalTime?> {
    read()
    val minutesAgo = durationsAgo.distinct()
    val days = daysAgo.distinct()
    // A duration ago already says the day and time: anything else said contradicts it
    val contradicts = minutesAgo.isNotEmpty() && (days.isNotEmpty() || clockTimes.isNotEmpty())
    if (otherDay || minutesAgo.size > 1 || days.size > 1 || contradicts) return null to null
    minutesAgo.singleOrNull()?.let {
      val at = now.minusMinutes(it).truncatedTo(ChronoUnit.MINUTES)
      return at.toLocalDate() to at.toLocalTime()
    }
    val date = days.singleOrNull()?.let { now.toLocalDate().minusDays(it) }
    val day = date ?: now.toLocalDate()
    // Only the hours that have already come on that day are possible
    val times =
        clockTimes
            .map { (hours, minutes) ->
              hours
                  .map { LocalTime.of(it, minutes) }
                  .singleOrNull { !day.atTime(it).atZone(now.zone).isAfter(now) }
            }
            .distinct()
    return date to times.singleOrNull()
  }

  private fun read() {
    var i = 0
    while (i < words.size) {
      i +=
          readDurationAgo(i).takeIf { it > 0 }
              ?: readDay(i).takeIf { it > 0 }
              ?: readOtherDay(i).takeIf { it > 0 }
              ?: readClock(i).takeIf { it > 0 }
              ?: 1
    }
  }

  /** "2 hours ago", "il y a 2 heures": how many words it takes, or 0. */
  private fun readDurationAgo(i: Int): Int {
    val before = anyAt(words, i, vocabulary.agoBefore)
    if (before > 0) {
      val (minutes, length) = durationAt(i + before) ?: return 0
      durationsAgo += minutes
      return before + length
    }
    val (minutes, length) = durationAt(i) ?: return 0
    val after = anyAt(words, i + length, vocabulary.agoAfter)
    if (after == 0) return 0
    durationsAgo += minutes
    return length + after
  }

  /** A duration in minutes at [i] ("2 hours", "an hour", "3h", "half an hour"), and its length. */
  private fun durationAt(i: Int): Pair<Long, Int>? {
    val half = anyAt(words, i, vocabulary.halfHour)
    if (half > 0) return 30L to half
    Regex("(\\d+)h").matchEntire(words.getOrNull(i) ?: return null)?.let {
      return it.groupValues[1].toLong() * 60 to 1
    }
    val count =
        numberAt(words, i, vocabulary) ?: (if (words[i] in vocabulary.articles) 1 else return null)
    val unit = words.getOrNull(i + 1)
    val minutes =
        when (unit) {
          in vocabulary.minuteUnits -> count.toLong()
          in vocabulary.hourUnits -> count * 60L
          else -> return null
        }
    return minutes.takeIf { it > 0 }?.let { it to 2 }
  }

  /** "yesterday", "avant-hier": how many words it takes, or 0. */
  private fun readDay(i: Int): Int {
    val (length, days) = longestAt(words, i, vocabulary.daysAgo) ?: return 0
    daysAgo += days
    return length
  }

  /** "tomorrow", "lundi": a day that can't be placed. How many words it takes, or 0. */
  private fun readOtherDay(i: Int): Int =
      anyAt(words, i, vocabulary.otherDays).also { if (it > 0) otherDay = true }

  /**
   * A time of day: "15h30", "3:30 pm", "9 o'clock", "8 heures du soir". A number alone isn't one.
   * Without "am" or "pm", an hour up to 12 could be morning or evening. Returns how many words it
   * takes, or 0.
   */
  private fun readClock(i: Int): Int {
    val word = words[i]
    val clock = Regex("(\\d{1,2})h(\\d{2})?").matchEntire(word)
    var length: Int
    val hour: Int
    val minutes: Int
    if (clock != null) {
      hour = clock.groupValues[1].toInt()
      minutes = clock.groupValues[2].ifEmpty { "0" }.toInt()
      length = 1
    } else {
      hour = numberAt(words, i, vocabulary) ?: return 0
      minutes = 0
      length = 1 + anyAt(words, i + 1, vocabulary.clockWords)
    }
    val morning = anyAt(words, i + length, vocabulary.morning)
    val afternoon = anyAt(words, i + length, vocabulary.afternoon)
    // A number needs "o'clock", "heures", "am" or "pm" to be a time
    if (length == 1 && clock == null && morning + afternoon == 0) return 0
    if (hour > 23 || minutes > 59) return 0
    length += morning + afternoon
    val hours =
        when {
          hour > 12 || hour == 0 -> listOf(hour)
          morning > 0 -> listOf(hour % 12)
          afternoon > 0 -> listOf(hour % 12 + 12)
          else -> listOf(hour % 12, hour % 12 + 12)
        }
    clockTimes += hours to minutes
    return length
  }
}
