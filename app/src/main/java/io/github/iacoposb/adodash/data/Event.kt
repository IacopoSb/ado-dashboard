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
) {
    /** The build of a [EventKind.BUILD_FAILED] event, in [project]. */
    val buildId: Int?
        get() = if (kind == EventKind.BUILD_FAILED) key.removePrefix(BUILD_PREFIX).substringBefore(':').toIntOrNull() else null

    companion object {
        const val BUILD_PREFIX = "build:"

        fun buildFailedKey(buildId: Int) = "$BUILD_PREFIX$buildId:failed"
    }
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE hidden = 0 ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<Event>>

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

    @Query("DELETE FROM events WHERE at < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [Event::class], version = 3, exportSchema = false)
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
    }
}
