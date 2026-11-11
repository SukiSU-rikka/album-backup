package cn.rikka.album

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一条备份记录：对应相册里的一个照片/视频 */
data class Item(
    val id: Long = 0,
    val mediaId: Long,
    val path: String,      // 现在在手机上的绝对路径（整理移动后会更新）
    val orig: String,      // 第一次备份时的路径
    val name: String,
    val mime: String,
    val size: Long,
    val mtime: Long,       // 修改时间，秒
    val taken: Long,       // 拍摄时间，毫秒，0 = 没有
    val added: Long,       // 加入相册时间，秒
    val video: Boolean,
    val sha: String,
    val state: Int = ACTIVE,
    val goneAt: Long = 0,  // 在相册里被删的时间，毫秒
    val dueAt: Long = 0,   // 到期自动删除的时间，毫秒
    val backedAt: Long = 0
) {
    val folder: String get() = path.substringBeforeLast('/', "")
    /** 排序/归档用的时间（毫秒）：有拍摄时间用拍摄时间，没有用修改时间 */
    val sortTime: Long get() = if (taken > 0) taken else mtime * 1000

    companion object {
        const val ACTIVE = 0
        const val PENDING = 1
    }
}

/** 备份文件本体（同内容只存一份） */
data class Blob(
    val sha: String,
    val rel: String,       // 备份目录下的相对路径，如 files/2024/05/IMG_1.jpg
    val size: Long,
    val onPhone: Boolean,  // false = 已导出并清掉手机上的暂存
    val verifiedAt: Long,
    val exportedAt: Long
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "album.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE item(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            media_id INTEGER, path TEXT NOT NULL, orig TEXT, name TEXT, mime TEXT,
            size INTEGER, mtime INTEGER, taken INTEGER, added INTEGER, video INTEGER,
            sha TEXT NOT NULL, state INTEGER, gone_at INTEGER, due_at INTEGER, backed_at INTEGER)"""
        )
        db.execSQL("CREATE INDEX item_path ON item(path)")
        db.execSQL("CREATE INDEX item_sha ON item(sha)")
        db.execSQL("CREATE INDEX item_state ON item(state)")
        db.execSQL(
            """CREATE TABLE blob(
            sha TEXT PRIMARY KEY, rel TEXT NOT NULL, size INTEGER,
            on_phone INTEGER, verified_at INTEGER, exported_at INTEGER)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------ item

    private fun Cursor.item() = Item(
        id = getLong(0), mediaId = getLong(1), path = getString(2), orig = getString(3) ?: "",
        name = getString(4) ?: "", mime = getString(5) ?: "", size = getLong(6), mtime = getLong(7),
        taken = getLong(8), added = getLong(9), video = getInt(10) != 0, sha = getString(11),
        state = getInt(12), goneAt = getLong(13), dueAt = getLong(14), backedAt = getLong(15)
    )

    private val cols = "id,media_id,path,orig,name,mime,size,mtime,taken,added,video,sha,state,gone_at,due_at,backed_at"

    private fun items(where: String?, args: Array<String>? = null, order: String? = null, limit: String? = null): List<Item> {
        val out = ArrayList<Item>()
        val sql = buildString {
            append("SELECT $cols FROM item")
            if (where != null) append(" WHERE $where")
            if (order != null) append(" ORDER BY $order")
            if (limit != null) append(" LIMIT $limit")
        }
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(c.item()) }
        return out
    }

    fun active(): List<Item> = items("state=0")
    fun pending(): List<Item> = items("state=1", order = "due_at ASC")
    fun all(): List<Item> = items(null, order = "taken ASC")
    fun byId(id: Long): Item? = items("id=?", arrayOf(id.toString())).firstOrNull()
    fun bySha(sha: String): List<Item> = items("sha=?", arrayOf(sha))
    fun recent(n: Int): List<Item> = items("state=0", order = "backed_at DESC", limit = n.toString())
    fun pendingDue(now: Long): List<Item> = items("state=1 AND due_at<=?", arrayOf(now.toString()))

    private fun Item.cv() = ContentValues().apply {
        put("media_id", mediaId); put("path", path); put("orig", orig); put("name", name)
        put("mime", mime); put("size", size); put("mtime", mtime); put("taken", taken)
        put("added", added); put("video", if (video) 1 else 0); put("sha", sha); put("state", state)
        put("gone_at", goneAt); put("due_at", dueAt); put("backed_at", backedAt)
    }

    fun insert(it: Item): Long = writableDatabase.insertOrThrow("item", null, it.cv())
    fun update(it: Item) { writableDatabase.update("item", it.cv(), "id=?", arrayOf(it.id.toString())) }
    fun deleteItem(id: Long) { writableDatabase.delete("item", "id=?", arrayOf(id.toString())) }

    fun countActive(): Int = count("SELECT COUNT(*) FROM item WHERE state=0")
    fun countActiveVideo(): Int = count("SELECT COUNT(*) FROM item WHERE state=0 AND video=1")
    fun countPending(): Int = count("SELECT COUNT(*) FROM item WHERE state=1")
    fun countItems(): Int = count("SELECT COUNT(*) FROM item")

    private fun count(sql: String, args: Array<String>? = null): Int =
        readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    // ------------------------------------------------------------ blob

    private fun Cursor.blob() = Blob(
        sha = getString(0), rel = getString(1), size = getLong(2), onPhone = getInt(3) != 0,
        verifiedAt = getLong(4), exportedAt = getLong(5)
    )

    fun blob(sha: String): Blob? =
        readableDatabase.rawQuery(
            "SELECT sha,rel,size,on_phone,verified_at,exported_at FROM blob WHERE sha=?", arrayOf(sha)
        ).use { c -> if (c.moveToFirst()) c.blob() else null }

    fun blobs(): List<Blob> {
        val out = ArrayList<Blob>()
        readableDatabase.rawQuery("SELECT sha,rel,size,on_phone,verified_at,exported_at FROM blob ORDER BY rel", null)
            .use { c -> while (c.moveToNext()) out.add(c.blob()) }
        return out
    }

    fun blobRels(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT rel FROM blob", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun putBlob(b: Blob) {
        writableDatabase.insertWithOnConflict("blob", null, ContentValues().apply {
            put("sha", b.sha); put("rel", b.rel); put("size", b.size)
            put("on_phone", if (b.onPhone) 1 else 0); put("verified_at", b.verifiedAt); put("exported_at", b.exportedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteBlob(sha: String) { writableDatabase.delete("blob", "sha=?", arrayOf(sha)) }
    fun refs(sha: String): Int = count("SELECT COUNT(*) FROM item WHERE sha=?", arrayOf(sha))

    fun phoneBytes(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size),0) FROM blob WHERE on_phone=1", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    /** 最早一次备份的时间（毫秒），没有返回 0 */
    fun firstBackedAt(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MIN(backed_at),0) FROM item", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    fun countBlobs(): Int = count("SELECT COUNT(*) FROM blob")
    fun countOffloaded(): Int = count("SELECT COUNT(*) FROM blob WHERE on_phone=0")
    fun countUnexported(): Int = count("SELECT COUNT(*) FROM blob WHERE on_phone=1 AND exported_at=0")

    fun tx(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM item")
        writableDatabase.execSQL("DELETE FROM blob")
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}
