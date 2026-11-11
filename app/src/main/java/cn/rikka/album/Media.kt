package cn.rikka.album

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 系统媒体库里的一项（相册里能看到的照片/视频） */
class MediaEntry(
    val id: Long,
    val path: String,
    val name: String,
    val mime: String,
    val size: Long,
    val mtime: Long,     // 秒
    val taken: Long,     // 毫秒
    val added: Long,     // 秒
    val video: Boolean,
    val width: Int,
    val height: Int
) {
    /** 相对主存储的目录，如 "DCIM/Camera/" */
    val rel: String get() = Media.relOf(path)
    val uri: Uri get() = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
    )
}

object Media {
    const val PRIMARY = "/storage/emulated/0/"

    fun relOf(path: String): String {
        val p = if (path.startsWith(PRIMARY)) path.substring(PRIMARY.length) else path.trimStart('/')
        val i = p.lastIndexOf('/')
        return if (i < 0) "" else p.substring(0, i + 1)
    }

    fun userPath(path: String): String = if (path.startsWith(PRIMARY)) path.substring(PRIMARY.length) else path

    /**
     * 枚举主存储上所有照片和视频。
     * 只看 /storage/emulated/0（内置存储），隐藏目录（.thumbnails、回收站等）跳过；
     * 写入中的文件（IS_PENDING=1）系统默认就不返回。
     */
    fun scan(ctx: Context, includeVideo: Boolean = true): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        val uri = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaColumns._ID, MediaColumns.DATA, MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE,
            MediaColumns.SIZE, MediaColumns.DATE_MODIFIED, MediaColumns.DATE_TAKEN, MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaColumns.WIDTH, MediaColumns.HEIGHT
        )
        val types = if (includeVideo) "1,3" else "1"
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)"
        ctx.contentResolver.query(uri, proj, sel, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                if (!path.startsWith(PRIMARY)) continue
                if (isHidden(path)) continue
                val name = c.getString(2) ?: path.substringAfterLast('/')
                out.add(
                    MediaEntry(
                        id = c.getLong(0), path = path, name = name, mime = c.getString(3) ?: "",
                        size = c.getLong(4), mtime = c.getLong(5),
                        taken = if (c.isNull(6)) 0 else c.getLong(6),
                        added = c.getLong(7), video = c.getInt(8) == 3,
                        width = c.getInt(9), height = c.getInt(10)
                    )
                )
            }
        }
        return out
    }

    /** 媒体库里照片+视频总数（安全核对用，含视频） */
    fun count(ctx: Context): Int = scan(ctx, true).size

    fun isHidden(path: String): Boolean =
        userPath(path).split('/').dropLast(1).any { it.startsWith(".") } ||
            userPath(path).startsWith("Android/")

    /** 让系统媒体库重新扫描这些路径（新增、删除、移动后都要调用），最多等 60 秒 */
    fun rescan(ctx: Context, paths: Collection<String>) {
        if (paths.isEmpty()) return
        for (chunk in paths.chunked(200)) {
            val latch = CountDownLatch(chunk.size)
            MediaScannerConnection.scanFile(ctx, chunk.toTypedArray(), null) { _, _ -> latch.countDown() }
            latch.await(60, TimeUnit.SECONDS)
        }
    }

    /** 按绝对路径查媒体库里的记录（还原后核对时间用） */
    fun byPath(ctx: Context, path: String): MediaEntry? {
        val uri = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaColumns._ID, MediaColumns.DATA, MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE,
            MediaColumns.SIZE, MediaColumns.DATE_MODIFIED, MediaColumns.DATE_TAKEN, MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaColumns.WIDTH, MediaColumns.HEIGHT
        )
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(uri, proj, "${MediaColumns.DATA}=?", arrayOf(path), null)?.use { c ->
            if (c.moveToFirst()) return MediaEntry(
                c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4),
                c.getLong(5), if (c.isNull(6)) 0 else c.getLong(6), c.getLong(7), c.getInt(8) == 3,
                c.getInt(9), c.getInt(10)
            )
        }
        return null
    }
}
