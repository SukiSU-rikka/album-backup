package cn.rikka.album

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** 对用户选的文件夹（SAF）做读写，目录列表有缓存，避免每个文件都去查一遍 */
class Saf(ctx: Context, private val tree: Uri) {
    class Child(val docId: String, val size: Long, val isDir: Boolean)

    private val cr = ctx.contentResolver
    val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val cache = HashMap<String, HashMap<String, Child>>()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun children(parentId: String): HashMap<String, Child> = cache.getOrPut(parentId) {
        val map = HashMap<String, Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val size = if (c.isNull(2)) -1L else c.getLong(2)
                val isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                map[name] = Child(c.getString(0), size, isDir)
            }
        }
        map
    }

    fun rootChildren(): HashMap<String, Child> = children(rootId)

    /** 逐级创建（已有则复用）目录，返回最后一级的 docId */
    fun ensureDir(segments: List<String>): String {
        var cur = rootId
        for (s in segments) {
            val ch = children(cur)
            val ex = ch[s]
            cur = if (ex != null && ex.isDir) {
                ex.docId
            } else {
                val uri = DocumentsContract.createDocument(
                    cr, docUri(cur), DocumentsContract.Document.MIME_TYPE_DIR, s
                ) ?: throw IOException("无法创建文件夹 $s")
                val id = DocumentsContract.getDocumentId(uri)
                ch[s] = Child(id, -1L, true)
                id
            }
        }
        return cur
    }

    fun createFile(parentId: String, name: String, size: Long): String {
        // 用 octet-stream，避免部分系统自作主张改文件扩展名
        val uri = DocumentsContract.createDocument(
            cr, docUri(parentId), "application/octet-stream", name
        ) ?: throw IOException("无法在备份文件夹里创建文件")
        val id = DocumentsContract.getDocumentId(uri)
        children(parentId)[name] = Child(id, size, false)
        return id
    }

    fun delete(parentId: String, name: String, docId: String) {
        try {
            DocumentsContract.deleteDocument(cr, docUri(docId))
        } catch (_: Exception) {
        }
        children(parentId).remove(name)
    }

    fun resolve(path: String): Child? {
        var cur = rootId
        val parts = path.split("/")
        for ((i, p) in parts.withIndex()) {
            val c = children(cur)[p] ?: return null
            if (i == parts.lastIndex) return c
            if (!c.isDir) return null
            cur = c.docId
        }
        return null
    }

    fun openIn(docId: String): InputStream =
        cr.openInputStream(docUri(docId)) ?: throw IOException("无法读取备份文件")

    fun openOut(docId: String): OutputStream =
        cr.openOutputStream(docUri(docId), "wt") ?: throw IOException("无法写入备份文件夹")
}
