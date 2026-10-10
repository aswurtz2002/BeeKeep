package com.beekeep.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ApiaryEntity::class, HiveEntity::class, InspectionEntity::class, FeedingEntity::class, TreatmentEntity::class, HarvestEntity::class, ActivityEventEntity::class, SyncOutboxEntity::class, TaskEntity::class, PhotoEntity::class, NfcTagAssignmentEntity::class],
    version = 9,
    exportSchema = false
)
abstract class BeeKeepRoomDb : RoomDatabase() {
    abstract fun apiaries(): ApiaryDao
    abstract fun hives(): HiveDao
    abstract fun inspections(): InspectionDao
    abstract fun feedings(): FeedingDao
    abstract fun treatments(): TreatmentDao
    abstract fun harvests(): HarvestDao
    abstract fun events(): EventDao
    abstract fun outbox(): OutboxDao
    abstract fun photos(): PhotoDao
    abstract fun nfcTagAssignments(): NfcTagAssignmentDao
    abstract fun tasks(): TaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hive_components (
                        id INTEGER NOT NULL PRIMARY KEY,
                        hive_id INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        component_type TEXT NOT NULL,
                        label TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        notes TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted INTEGER NOT NULL,
                        FOREIGN KEY(hive_id) REFERENCES hives(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hive_components_hive_id ON hive_components(hive_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hive_components_position ON hive_components(position)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inspection_photos (
                        id INTEGER NOT NULL PRIMARY KEY,
                        hive_id INTEGER NOT NULL,
                        inspection_id INTEGER NOT NULL,
                        local_path TEXT NOT NULL,
                        cloud_path TEXT,
                        created_at INTEGER NOT NULL,
                        sync_state TEXT NOT NULL,
                        last_error TEXT,
                        FOREIGN KEY(hive_id) REFERENCES hives(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspection_photos_hive_id ON inspection_photos(hive_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspection_photos_inspection_id ON inspection_photos(inspection_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspection_photos_sync_state ON inspection_photos(sync_state)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS hive_components")
                db.execSQL("DELETE FROM sync_outbox WHERE entity_type = 'hive_component'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminder_enabled INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve the first assignment when older builds accidentally stored the same tag on multiple hives.
                db.execSQL("""
                    UPDATE hives
                    SET tag_uid = NULL
                    WHERE tag_uid IS NOT NULL
                      AND id NOT IN (
                        SELECT MIN(id) FROM hives WHERE tag_uid IS NOT NULL GROUP BY tag_uid
                      )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_hives_tag_uid ON hives(tag_uid)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET updated_at = due_at WHERE updated_at = 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Composite indexes match BeeKeep's hottest list, timeline, task, outbox, and photo queries.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hives_apiary_number ON hives(apiary, number)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspections_hive_id_created_at ON inspections(hive_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_feedings_hive_id_created_at ON feeding_entries(hive_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_treatments_hive_id_created_at ON treatment_entries(hive_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_harvests_hive_id_created_at ON harvest_entries(hive_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_hive_id_created_at ON activity_events(hive_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_state_created_at ON sync_outbox(state, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_entity_type_entity_id_state ON sync_outbox(entity_type, entity_id, state)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_completed_due_at ON tasks(completed, due_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_updated_at ON tasks(updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspection_photos_sync_state_created_at ON inspection_photos(sync_state, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inspection_photos_hive_id_created_at ON inspection_photos(hive_id, created_at)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hives ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
                db.execSQL("ALTER TABLE hives ADD COLUMN dead_at INTEGER")
                db.execSQL("ALTER TABLE hives ADD COLUMN status_changed_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE hives SET status_changed_at = updated_at WHERE status_changed_at = 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS nfc_tag_assignments (
                        id INTEGER NOT NULL PRIMARY KEY,
                        tag_uid TEXT NOT NULL,
                        hive_id INTEGER NOT NULL,
                        assigned_at INTEGER NOT NULL,
                        unassigned_at INTEGER,
                        FOREIGN KEY(hive_id) REFERENCES hives(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_tag_assignments_hive_id ON nfc_tag_assignments(hive_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_nfc_tag_assignments_tag_uid ON nfc_tag_assignments(tag_uid)")
                // Existing tag assignments move into the new ledger; hives.tag_uid stays as the cached current tag.
                db.execSQL("""
                    INSERT INTO nfc_tag_assignments (id, tag_uid, hive_id, assigned_at)
                    SELECT id, tag_uid, id, updated_at FROM hives WHERE tag_uid IS NOT NULL
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hives ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE hives ADD COLUMN longitude REAL")
                // Promote each hive's most recent inspection GPS to its initial permanent location.
                // The Apiary coordinates stay on their separate apiaries row.
                db.execSQL("""
                    UPDATE hives
                    SET latitude = (
                        SELECT i.latitude FROM inspections i
                        WHERE i.hive_id = hives.id AND i.latitude IS NOT NULL AND i.longitude IS NOT NULL
                        ORDER BY i.created_at DESC LIMIT 1
                    ),
                    longitude = (
                        SELECT i.longitude FROM inspections i
                        WHERE i.hive_id = hives.id AND i.latitude IS NOT NULL AND i.longitude IS NOT NULL
                        ORDER BY i.created_at DESC LIMIT 1
                    )
                    WHERE EXISTS (
                        SELECT 1 FROM inspections i
                        WHERE i.hive_id = hives.id AND i.latitude IS NOT NULL AND i.longitude IS NOT NULL
                    )
                """.trimIndent())
            }
        }

        @Volatile private var INSTANCE: BeeKeepRoomDb? = null
        fun get(context: Context): BeeKeepRoomDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, BeeKeepRoomDb::class.java, "beekeep_room.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
                .also { INSTANCE = it }
        }
    }
}
