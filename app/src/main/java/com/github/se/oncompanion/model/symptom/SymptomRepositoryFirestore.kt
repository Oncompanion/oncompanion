// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import android.util.Log
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectedWritesProvider
import com.github.se.oncompanion.model.session.SessionAccess
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [SymptomRepository] backed by Cloud Firestore, documents at `/users/{uid}/symptoms/{id}`.
 *
 * Writes return as soon as they are saved on the device, so logging a symptom never waits for the
 * network. If the server later rejects a write (security rules, revoked access), Firestore undoes
 * it on the device and it is reported to [rejectedWrites], so the user is told;
 * [SymptomEntry.isValid] mirrors the rules so this shouldn't happen.
 */
class SymptomRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
    private val rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
) : SymptomRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(
      db: FirebaseFirestore,
      rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
  ) : this({ db }, rejectedWrites)

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeSymptoms(uid: String): Flow<List<SymptomEntry>> = SessionAccess.snapshots {
    val registration =
        collection(uid)
            .orderBy(FIELD_OCCURRED_AT, Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else {
                trySend(snapshot?.documents?.mapNotNull(::fromDocument).orEmpty())
              }
            }
    awaitClose { registration.remove() }
  }

  override fun observeSymptom(uid: String, id: String): Flow<SymptomEntry?> =
      SessionAccess.snapshots {
        val registration =
            collection(uid).document(id).addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else {
                trySend(snapshot?.let(::fromDocument))
              }
            }
        awaitClose { registration.remove() }
      }

  override suspend fun addSymptom(uid: String, entry: SymptomEntry): String =
      SessionAccess.operation {
        require(entry.isValid()) { "Invalid symptom entry" }
        // The ID is generated on the device, so it is known right away, also offline
        val document = collection(uid).document()
        document
            .set(
                fieldsOf(entry) +
                    (FIELD_STRENGTHS to entry.strengths.mapKeys { (type, _) -> type.name }) +
                    attributionOf(entry) +
                    (FIELD_CREATED_AT to FieldValue.serverTimestamp())
            )
            .addOnFailureListener { e ->
              Log.e(TAG, "The server rejected the symptom ${document.id}; it was rolled back", e)
              rejectedWrites.report()
            }
        return@operation document.id
      }

  override suspend fun updateSymptom(uid: String, entry: SymptomEntry): Unit =
      SessionAccess.operation {
        require(entry.id.isNotBlank()) { "The symptom to update has no ID" }
        require(entry.isValid()) { "Invalid symptom entry" }
        // update() only changes an existing document, unlike set(); createdAt is left untouched.
        // Not
        // awaited, like deleteSymptom
        collection(uid)
            .document(entry.id)
            .update(fieldsOf(entry) + strengthsUpdateOf(entry) + editorOf(entry))
            .addOnFailureListener { e ->
              Log.e(
                  TAG,
                  "Couldn't update the symptom ${entry.id} (deleted or rejected); rolled back",
                  e,
              )
              rejectedWrites.report()
            }
      }

  override suspend fun deleteSymptom(uid: String, id: String): Unit = SessionAccess.operation {
    // Not awaited: the task only completes once the server confirms, which never happens offline
    collection(uid).document(id).delete().addOnFailureListener { e ->
      Log.e(TAG, "The server rejected deleting the symptom $id; it was restored", e)
      rejectedWrites.report()
    }
  }

  /**
   * Who added [entry], only for a caregiver's entry: the rules refuse these fields on the patient's
   * own entries, and an update never changes them.
   */
  private fun attributionOf(entry: SymptomEntry): Map<String, Any?> =
      if (entry.addedBy == null) emptyMap()
      else mapOf(FIELD_ADDED_BY to entry.addedBy, FIELD_ADDED_BY_NAME to entry.addedByName)

  /**
   * Who edited [entry]: the caregiver who signed it (US-46), or nobody when the patient did, which
   * removes an earlier caregiver's name since the patient's edit is now the last one.
   */
  private fun editorOf(entry: SymptomEntry): Map<String, Any?> =
      mapOf(
          FIELD_EDITED_BY to (entry.editedBy ?: FieldValue.delete()),
          FIELD_EDITED_BY_NAME to
              (entry.editedByName.takeIf { entry.editedBy != null } ?: FieldValue.delete()),
      )

  /**
   * Every symptom's strength for an update, by field path, with [NO_STRENGTH] for each symptom
   * without one. Not a deletion: offline, the device's copy doesn't apply a deletion inside a map
   * (the server does once it syncs), so a strength the user removed would still show. Written for
   * every symptom, so one removed from the entry can't bring its old strength back if re-added.
   */
  private fun strengthsUpdateOf(entry: SymptomEntry): Map<String, Any> =
      SymptomType.entries.associate { type ->
        "$FIELD_STRENGTHS.${type.name}" to (entry.strengths[type] ?: NO_STRENGTH)
      }

  /** What the user entered, as stored in Firestore (everything except the ID and createdAt). */
  private fun fieldsOf(entry: SymptomEntry): Map<String, Any?> =
      mapOf(
          FIELD_TYPES to entry.types.map { it.name },
          FIELD_INTENSITY to entry.intensity,
          FIELD_OCCURRED_AT to Timestamp(entry.occurredAt),
          FIELD_NOTES to entry.notes,
          // Written even without "Other": an edit that removes it clears the name
          FIELD_OTHER_NAME to entry.otherName.takeIf { SymptomType.OTHER in entry.types },
      )

  private fun collection(uid: String) =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  private fun fromDocument(doc: DocumentSnapshot): SymptomEntry? {
    if (!doc.exists()) return null
    return try {
      // Entries saved before several symptoms could be logged have no types: they are skipped
      val types = (doc.get(FIELD_TYPES) as List<*>).map { SymptomType.valueOf(it as String) }
      SymptomEntry(
          id = doc.id,
          types = types,
          intensity = doc.getLong(FIELD_INTENSITY)?.toInt(),
          strengths = strengthsIn(doc, types),
          occurredAt = doc.getTimestamp(FIELD_OCCURRED_AT)!!.toInstant(),
          notes = doc.getString(FIELD_NOTES),
          otherName = otherNameIn(doc, types),
          // Until the server sets it, the time the device saved it: entries saved offline keep
          // the order they were saved in
          createdAt = doc.getTimestamp(FIELD_CREATED_AT, ESTIMATE)?.toInstant(),
          addedBy = doc.getString(FIELD_ADDED_BY),
          addedByName = doc.getString(FIELD_ADDED_BY_NAME),
          editedBy = doc.getString(FIELD_EDITED_BY),
          editedByName = doc.getString(FIELD_EDITED_BY_NAME),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Malformed symptom document ${doc.id}", e)
      null
    }
  }

  /**
   * The name of the "Other" symptom saved in [doc], or `null`: entries saved before it existed have
   * none, and an older app version can remove "Other" from an entry and leave its name behind.
   */
  private fun otherNameIn(doc: DocumentSnapshot, types: List<SymptomType>): String? =
      (doc.get(FIELD_OTHER_NAME) as? String)?.takeIf {
        SymptomType.OTHER in types && it.isNotBlank()
      }

  /**
   * The strengths saved in [doc], for the entry's [types] only. [NO_STRENGTH] is a strength removed
   * by an edit. A strength an older app version left behind (it doesn't know them and can remove a
   * symptom without its strength), or one a newer app version could write (another symptom, a value
   * out of range), is left out rather than losing the whole entry.
   */
  private fun strengthsIn(doc: DocumentSnapshot, types: List<SymptomType>): Map<SymptomType, Int> {
    val saved = doc.get(FIELD_STRENGTHS) as? Map<*, *> ?: return emptyMap()
    return saved.entries
        .mapNotNull { (name, value) ->
          val type = types.firstOrNull { it.name == name }
          val strength =
              (value as? Long)?.takeIf {
                it in SymptomEntry.MIN_INTENSITY.toLong()..SymptomEntry.MAX_INTENSITY.toLong()
              }
          if (type != null && strength != null) type to strength.toInt() else null
        }
        .toMap()
  }

  companion object {
    const val USERS_COLLECTION = "users"
    const val COLLECTION = "symptoms"
    const val FIELD_TYPES = "types"
    const val FIELD_INTENSITY = "intensity"
    /** How strong each symptom was, by [SymptomType] name: `{"FATIGUE": 5}`. */
    const val FIELD_STRENGTHS = "strengths"
    /** Saved for a symptom whose strength an edit removed; read as no strength. */
    const val NO_STRENGTH = 0
    const val FIELD_OCCURRED_AT = "occurredAt"
    const val FIELD_NOTES = "notes"
    const val FIELD_OTHER_NAME = "otherName"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_ADDED_BY = "addedBy"
    const val FIELD_ADDED_BY_NAME = "addedByName"
    /** The uid of the caregiver who last edited it (US-46); absent when the patient did. */
    const val FIELD_EDITED_BY = "editedBy"
    /** That caregiver's first name, as shown in "Edited by". */
    const val FIELD_EDITED_BY_NAME = "editedByName"
    private const val TAG = "SymptomRepository"
  }
}
