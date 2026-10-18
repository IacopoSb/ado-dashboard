package io.github.iacoposb.adodash.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The events' database across versions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EventDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `v4 events count as stored at the update, so none is pruned for being old`() {
        val name = "events-v4.db"
        context.deleteDatabase(name)
        val v4 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `events` (`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                                "`body` TEXT NOT NULL, `url` TEXT NOT NULL, `at` INTEGER NOT NULL, `read` INTEGER NOT NULL, " +
                                "`hidden` INTEGER NOT NULL, `project` TEXT, `area` TEXT, `author` TEXT, PRIMARY KEY(`key`))",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                },
            ).build(),
        )
        // Happened in Azure DevOps a year ago
        val yearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
        v4.writableDatabase.execSQL(
            "INSERT INTO events VALUES ('wi:1:assigned', 'ASSIGNED', 'title', 'body', 'url', $yearAgo, 0, 0, 'Contoso', 'Contoso', NULL)",
        )
        v4.close()

        val before = System.currentTimeMillis()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                val event = db.events().recentOnce().single()
                assertEquals("wi:1:assigned", event.key)
                assertEquals(yearAgo, event.at)
                assertTrue("stored at ${event.storedAt}, migrated at $before", event.storedAt >= before)
                // A sync's pruning keeps it: stored now, not a year ago
                db.events().prune(before = before - 30L * 24 * 3600 * 1000)
                assertEquals(listOf("wi:1:assigned"), db.events().keys())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
