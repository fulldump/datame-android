package app.datame.movil

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** What the house already has: so nothing is sent twice. */
data class Sent(val size: Long, val modifiedSeconds: Long, val path: String)

/**
 * The files already in the house, by their key. A file whose size or date
 * changed (a photo edited on the phone) is sent again over the same path: the
 * house keeps the earlier version in its history.
 */
class UploadLog(context: Context) : SQLiteOpenHelper(context, "uploads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sent (" +
                "key TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "path TEXT NOT NULL, at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Everything sent, to compare with what the phone has in one go. */
    fun all(): Map<String, Sent> {
        val out = HashMap<String, Sent>()
        readableDatabase.rawQuery("SELECT key, size, modified, path FROM sent", null).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] = Sent(cursor.getLong(1), cursor.getLong(2), cursor.getString(3))
            }
        }
        return out
    }

    fun record(key: String, size: Long, modifiedSeconds: Long, path: String) {
        writableDatabase.insertWithOnConflict(
            "sent", null,
            ContentValues().apply {
                put("key", key)
                put("size", size)
                put("modified", modifiedSeconds)
                put("path", path)
                put("at", System.currentTimeMillis() / 1000)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun count(): Long = readableDatabase.compileStatement("SELECT COUNT(*) FROM sent").simpleQueryForLong()

    /** Paths already used, so two files with the same name do not take the same one. */
    fun pathTaken(path: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM sent WHERE path = ? LIMIT 1", arrayOf(path)).use { it.moveToFirst() }

    fun clear() {
        writableDatabase.delete("sent", null, null)
    }
}
