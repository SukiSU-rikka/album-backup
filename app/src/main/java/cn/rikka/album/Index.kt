package cn.rikka.album

import android.util.JsonReader
import android.util.JsonWriter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/** 备份索引里的一条记录：恢复时靠它把时间等信息原样带回去 */
data class Entry(
    val path: String,        // 在备份文件夹里的相对路径
    val name: String,        // 原文件名
    val mime: String,
    val size: Long,
    val dateTaken: Long,     // 毫秒，0 表示原本没有
    val dateModified: Long,  // 秒
    val dateAdded: Long,     // 秒
    val album: String,       // 原来所在的目录，仅作备用
    val video: Boolean
) {
    val key: String get() = "$name|$size"
}

object Index {
    const val FILE = "backup_index.json"

    fun write(out: OutputStream, list: List<Entry>) {
        val w = JsonWriter(BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 65536))
        w.beginObject()
        w.name("version").value(1)
        w.name("items").beginArray()
        for (e in list) {
            w.beginObject()
            w.name("path").value(e.path)
            w.name("name").value(e.name)
            w.name("mime").value(e.mime)
            w.name("size").value(e.size)
            w.name("taken").value(e.dateTaken)
            w.name("modified").value(e.dateModified)
            w.name("added").value(e.dateAdded)
            w.name("album").value(e.album)
            w.name("video").value(e.video)
            w.endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
    }

    fun read(ins: InputStream): List<Entry> {
        val r = JsonReader(BufferedReader(InputStreamReader(ins, Charsets.UTF_8), 65536))
        val list = ArrayList<Entry>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "items" -> {
                    r.beginArray()
                    while (r.hasNext()) list.add(readEntry(r))
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return list
    }

    private fun readEntry(r: JsonReader): Entry {
        var path = ""
        var name = ""
        var mime = ""
        var size = 0L
        var taken = 0L
        var modified = 0L
        var added = 0L
        var album = ""
        var video = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "path" -> path = r.nextString()
                "name" -> name = r.nextString()
                "mime" -> mime = r.nextString()
                "size" -> size = r.nextLong()
                "taken" -> taken = r.nextLong()
                "modified" -> modified = r.nextLong()
                "added" -> added = r.nextLong()
                "album" -> album = r.nextString()
                "video" -> video = r.nextBoolean()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Entry(path, name, mime, size, taken, modified, added, album, video)
    }
}
