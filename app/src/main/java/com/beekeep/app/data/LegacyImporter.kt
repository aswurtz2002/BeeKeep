package com.beekeep.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import kotlin.math.max

/** Imports v0.2's raw SQLite database once into the new Room database. */
object LegacyImporter {
    private const val PREFS = "beekeep_migrations"
    private const val IMPORT_COMPLETE = "legacy_import_complete"

    suspend fun importIfNeeded(context: Context, db: BeeKeepRoomDb) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(IMPORT_COMPLETE, false)) return
        val legacyFile = context.getDatabasePath("beekeep.db")
        if (!legacyFile.exists() || !legacyFile.isFile) {
            prefs.edit().putBoolean(IMPORT_COMPLETE, true).apply()
            return
        }
        try {
            val legacy = SQLiteDatabase.openDatabase(legacyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            legacy.use { source ->
                db.withTransaction {
                    if (source.hasTable("hives")) {
                        source.rawQuery("SELECT id,number,apiary,queen_status,strength,mite_percent,tag_uid FROM hives", null).use { c ->
                            while (c.moveToNext()) {
                                val hive = HiveEntity(c.getLong(0), c.getString(1), c.getString(2), null, c.getString(3), strength = c.getInt(4), mitePercent = c.getDouble(5), tagUid = c.getString(6))
                                db.hives().upsert(hive)
                                hive.tagUid?.let { db.nfcTagAssignments().upsert(NfcTagAssignmentEntity(hive.id, it, hive.id, System.currentTimeMillis())) }
                            }
                        }
                    }
                    if (source.hasTable("inspections")) {
                        source.rawQuery("SELECT id,hive_id,created_at,strength,queen_status,mite_count,sample_size,notes,photo_path,latitude,longitude,emergency_cells,supercedure_cells,swarm_cells FROM inspections", null).use { c ->
                            while (c.moveToNext()) {
                                val sample = max(1, c.getInt(6))
                                db.inspections().upsert(InspectionEntity(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4), c.getInt(5), sample, c.getString(7), c.getString(8), if (c.isNull(9)) null else c.getDouble(9), if (c.isNull(10)) null else c.getDouble(10), c.getInt(11), c.getInt(12), c.getInt(13)))
                            }
                        }
                    }
                }
            }
            prefs.edit().putBoolean(IMPORT_COMPLETE, true).apply()
        } catch (_: Exception) {
            // Legacy data is optional; a failed import should never block a fresh install.
        }
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean = rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}
