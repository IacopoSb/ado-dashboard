package io.github.iacoposb.adodash.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class EventKind {
    ASSIGNED, MENTION, PR_REVIEW, PR_COMMENT, PR_VOTE,
    /** A build of one of my pull requests failed; the key carries the build id (see [Event.buildId]). */
    BUILD_FAILED,
}

/**
 * Something that happened in Azure DevOps. [key] identifies it across
 * syncs, so the same event is never notified twice.
 */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey val key: String,
    val kind: EventKind,
    val title: String,
    val body: String,
    val url: String,
    /** When it happened in Azure DevOps (epoch ms). */
    val at: Long,
    val read: Boolean = false,
    /** What already existed at the first sync: kept to recognize it, never shown. */
    val hidden: Boolean = false,
    /** What the event is about, to show it only while the filters include it; null before v3. */
    val project: String? = null,
    /** Area path of a work item; null for pull requests, which have none. */
    val area: String? = null,
    /** Who wrote a comment or a mention, for the widgets; null for the rest and before v4. */
    val author: String? = null,
    /**
     * When the app stored it, or last found it again in a sync (epoch ms): what is pruned goes by
     * this, never by [at], which can be long before the event reached the app. 0 until stored.
     */
    val storedAt: Long = 0,
) {
    /** The build of a [EventKind.BUILD_FAILED] event, in [project]. */
    val buildId: Int?
        get() = if (kind == EventKind.BUILD_FAILED) key.removePrefix(BUILD_PREFIX).substringBefore(':').toIntOrNull() else null

    /** The work item and the comment of a mention in a work item's comments; null for the rest. */
    val workItemComment: WorkItemCommentRef?
        get() = if (kind == EventKind.MENTION) {
            WI_COMMENT.matchEntire(key)?.let { WorkItemCommentRef(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        } else {
            null
        }

    /** The pull request of a review, comment, mention or vote, with the comment for those. */
    val pullRequest: PrCommentRef?
        get() = PR_KEY.matchEntire(key)?.let { m ->
            PrCommentRef(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull())
        }

    companion object {
        const val BUILD_PREFIX = "build:"
        private val WI_COMMENT = Regex("wi:(\\d+):c(\\d+)")
        private val PR_KEY = Regex("pr:(\\d+)(?::t(\\d+):c(\\d+))?(?::.*)?")

        /** A comment of a pull request's thread, whoever wrote it. */
        fun prCommentKey(prId: Int, threadId: Int, commentId: Int) = "pr:$prId:t$threadId:c$commentId"

        fun buildFailedKey(buildId: Int) = "$BUILD_PREFIX$buildId:failed"

        /** A comment of a work item: the same key whoever wrote it, so that one stored stays. */
        fun workItemCommentKey(workItemId: Int, commentId: Int) = "wi:$workItemId:c$commentId"
    }
}

/** A comment of a work item, to open it there. */
data class WorkItemCommentRef(val workItemId: Int, val commentId: Int)

/** A pull request, and the thread and comment an event is about (null for reviews and votes). */
data class PrCommentRef(val prId: Int, val threadId: Int? = null, val commentId: Int? = null)

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE hidden = 0 ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<Event>>

    /** The same as [recent], once: for the widgets, outside any screen. */
    @Query("SELECT * FROM events WHERE hidden = 0 ORDER BY at DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int = 200): List<Event>

    /** Every event's key, the hidden ones too. */
    @Query("SELECT `key` FROM events")
    suspend fun keys(): List<String>

    /** Row ids, -1 for events already known. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(events: List<Event>): List<Long>

    @Query("UPDATE events SET read = 1 WHERE `key` = :key")
    suspend fun markRead(key: String)

    @Query("UPDATE events SET read = 1 WHERE `key` IN (:keys)")
    suspend fun markRead(keys: List<String>)

    @Query("UPDATE events SET read = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM events")
    suspend fun clear()

    /** [keys] were found again by a sync: kept for another retention period of the sync. */
    @Query("UPDATE events SET storedAt = :at WHERE `key` IN (:keys)")
    suspend fun touch(keys: List<String>, at: Long)

    /** Forgets what was neither stored nor found again since [before]. */
    @Query("DELETE FROM events WHERE storedAt < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [Event::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        /** v1 had no `hidden`: everything stored then came from the first sync. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE events SET hidden = 1")
            }
        }

        /** v3 knows what each event is about, for the filters. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN project TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN area TEXT")
            }
        }

        /** v4 knows who wrote a comment, for the widgets' latest mentions. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN author TEXT")
            }
        }

        /**
         * v5 prunes by when the app stored an event, not by when it happened: what is stored now
         * counts as stored at the update, so nothing is forgotten (and notified again) because of it.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN storedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE events SET storedAt = ?", arrayOf<Any>(System.currentTimeMillis()))
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
