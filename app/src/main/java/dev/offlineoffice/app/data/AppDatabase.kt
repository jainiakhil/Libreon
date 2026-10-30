// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import dev.offlineoffice.app.doc.DocumentKind
import kotlinx.coroutines.flow.Flow

/** How long we can keep using the document's URI. */
enum class GrantType {
    /** Persistable grant taken from the system picker; survives restarts. */
    PERSISTED,
    /** Temporary grant from "open with"/share; we also keep a private working copy. */
    TRANSIENT,
    /** The original grant is gone (process died); only the private copy is reachable. */
    PRIVATE_COPY,
}

/**
 * One open (or recently closed) document tab. [closedAt] is null while the tab is open.
 * [viewState] is an engine-specific JSON blob (scroll, page, cursor, cell, slide…).
 */
@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val kind: DocumentKind,
    val mimeType: String?,
    val grant: GrantType,
    val workingCopyPath: String?,
    val position: Int,
    val openedAt: Long,
    val lastActiveAt: Long,
    val viewState: String? = null,
    val dirty: Boolean = false,
    val recoveryPath: String? = null,
    val recoveryUpdatedAt: Long? = null,
    val sourceSize: Long? = null,
    val sourceModified: Long? = null,
    val closedAt: Long? = null,
)

@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val kind: DocumentKind,
    val mimeType: String?,
    val size: Long?,
    val lastOpenedAt: Long,
    val reopenable: Boolean,
    val pinned: Boolean = false,
)

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs WHERE closedAt IS NULL ORDER BY position")
    suspend fun openTabs(): List<TabEntity>

    @Query("SELECT * FROM tabs WHERE closedAt IS NOT NULL ORDER BY closedAt DESC LIMIT :limit")
    fun closedTabs(limit: Int = 20): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun get(id: String): TabEntity?

    @Upsert
    suspend fun upsert(tab: TabEntity)

    @Upsert
    suspend fun upsertAll(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun delete(id: String)

    /** Keep only the newest [keep] closed tabs. */
    @Query(
        "DELETE FROM tabs WHERE closedAt IS NOT NULL AND id NOT IN " +
            "(SELECT id FROM tabs WHERE closedAt IS NOT NULL ORDER BY closedAt DESC LIMIT :keep)",
    )
    suspend fun trimClosed(keep: Int = 20)
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY pinned DESC, lastOpenedAt DESC LIMIT 100")
    fun all(): Flow<List<RecentEntity>>

    @Upsert
    suspend fun upsert(recent: RecentEntity)

    @Query("DELETE FROM recents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recents")
    suspend fun clear()

    @Query("UPDATE recents SET pinned = :pinned WHERE uri = :uri")
    suspend fun setPinned(uri: String, pinned: Boolean)
}

class Converters {
    @TypeConverter fun kindToString(kind: DocumentKind): String = kind.name
    @TypeConverter fun stringToKind(value: String): DocumentKind = DocumentKind.valueOf(value)
    @TypeConverter fun grantToString(grant: GrantType): String = grant.name
    @TypeConverter fun stringToGrant(value: String): GrantType = GrantType.valueOf(value)
}

@Database(entities = [TabEntity::class, RecentEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tabs(): TabDao
    abstract fun recents(): RecentDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "offline-office.db").build()
    }
}
