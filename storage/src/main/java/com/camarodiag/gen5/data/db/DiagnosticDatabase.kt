package com.camarodiag.gen5.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "diagnostic_sessions")
data class SavedSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val vehicleLabel: String,
    val vin: String,
    val adapterName: String,
    val dtcSummary: String,
    val mileageSummary: String,
    val hasMileageDiscrepancy: Boolean,
    val maxDeltaKm: Double,
    @ColumnInfo(defaultValue = "''") val ownerUid: String = ""
)

@Dao
interface DiagnosticSessionDao {
    @Query("SELECT * FROM diagnostic_sessions WHERE ownerUid = :ownerUid ORDER BY timestamp DESC")
    fun observeSessionsForOwner(ownerUid: String): Flow<List<SavedSessionEntity>>

    @Query("UPDATE diagnostic_sessions SET ownerUid = :ownerUid WHERE ownerUid = ''")
    suspend fun claimUnassignedSessions(ownerUid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SavedSessionEntity)

    @Query("DELETE FROM diagnostic_sessions WHERE id = :id AND ownerUid = :ownerUid")
    suspend fun deleteSessionForOwner(id: Long, ownerUid: String)

    @Query("DELETE FROM diagnostic_sessions WHERE ownerUid = :ownerUid")
    suspend fun deleteAllForOwner(ownerUid: String)
}

@Database(entities = [SavedSessionEntity::class], version = 2, exportSchema = false)
abstract class DiagnosticDatabase : RoomDatabase() {
    abstract fun sessionDao(): DiagnosticSessionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve old history without assigning it silently to a new user.
                db.execSQL("ALTER TABLE diagnostic_sessions ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: DiagnosticDatabase? = null

        fun getInstance(context: Context): DiagnosticDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DiagnosticDatabase::class.java,
                    "camarodiag_gen5.db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
