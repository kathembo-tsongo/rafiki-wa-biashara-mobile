package com.example.llama.rafiki

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

private const val TAG = "RafikiRouter"
private const val PACK_ASSET = "rafiki_pack.db"

/** [PackStore] on Android's built-in SQLite (which includes FTS4 and the porter tokenizer). */
class AndroidPackStore(path: String) : PackStore, AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    override fun rows(sql: String, vararg args: String): List<List<Any?>> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<List<Any?>>()
            while (c.moveToNext()) {
                out.add((0 until c.columnCount).map { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                })
            }
            out
        }

    override fun close() = db.close()
}

object PackInstaller {
    /**
     * SQLite can only open a real file, not an APK asset, so the pack is copied into app
     * storage. It is re-copied only when the app is (re)installed, so a new pack shipped
     * in a new APK replaces the old one, and ordinary launches skip the ~30 MB copy.
     */
    fun install(context: Context): File {
        val dest = File(context.filesDir, PACK_ASSET)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val prefs = context.getSharedPreferences("rafiki_pack", Context.MODE_PRIVATE)
        if (dest.exists() && prefs.getLong("installed_for", -1L) == installed) return dest

        val tmp = File(context.filesDir, "$PACK_ASSET.tmp")
        val t0 = System.currentTimeMillis()
        context.assets.open(PACK_ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(dest)) {  // rename is atomic: a half-copied pack is never opened
            dest.delete()
            check(tmp.renameTo(dest)) { "could not install knowledge pack" }
        }
        prefs.edit().putLong("installed_for", installed).apply()
        Log.i(TAG, "pack copied to ${dest.path} (${dest.length() / 1_000_000} MB) in ${System.currentTimeMillis() - t0} ms")
        return dest
    }
}

/**
 * Temporary on-device check: routes every question in assets/parity.txt and logs one
 * line per question in the same format as `query_pack.py --parity`. Diff the two
 * outputs to confirm the phone routes exactly like the laptop. Read with:
 *     adb logcat -s RafikiRouter
 */
object RouterSelfTest {
    fun run(context: Context) {
        try {
            val t0 = System.currentTimeMillis()
            val pack = PackInstaller.install(context)
            AndroidPackStore(pack.path).use { store ->
                val sqliteVersion = store.rows("SELECT sqlite_version()").single()[0]
                val router = Router(store)
                Log.i(TAG, "SQLite $sqliteVersion, pack as_of ${router.asOf}, ready in ${System.currentTimeMillis() - t0} ms")
                val questions = context.assets.open("parity.txt").bufferedReader().readLines()
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                val times = ArrayList<Long>()
                for (q in questions) {
                    val s = System.nanoTime()
                    val r = router.route(q)
                    times.add((System.nanoTime() - s) / 1_000_000)
                    Log.i(TAG, router.parityLine(q, r))
                }
                times.sort()
                Log.i(TAG, "DONE ${questions.size} questions, median ${times[times.size / 2]} ms, max ${times.last()} ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "self-test FAILED", e)
        }
    }
}
