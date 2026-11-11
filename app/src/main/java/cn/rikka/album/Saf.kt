package cn.rikka.album

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/** 系统文件夹选择器（SAF）相关：把选中的文件夹转成真实路径，或在没 root 时按文档方式读取 */
object Saf {
    /** 外置存储提供者的树 URI → 真实路径。U 盘走 /mnt/media_rw/<UUID>（root 直接读写，能保留修改时间） */
    fun realPath(tree: Uri, rootOk: Boolean): String? {
        if (tree.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val vol = id.substringBefore(':')
        val sub = id.substringAfter(':', "").trim('/')
        val base = when {
            vol == "primary" -> "/storage/emulated/0"
            rootOk && Root.exists("/mnt/media_rw/$vol") -> "/mnt/media_rw/$vol"
            else -> "/storage/$vol"
        }
        return if (sub.isEmpty()) base else "$base/$sub"
    }

    /** 递归列出树里所有文件：相对路径 → 文档 URI（没 root 时从 U 盘还原用） */
    fun walk(ctx: Context, tree: Uri): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        fun go(docId: String, prefix: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) go(id, "$prefix$name/")
                    else out["$prefix$name"] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        go(DocumentsContract.getTreeDocumentId(tree), "")
        return out
    }
}

/** 还原的来源：本机备份目录 / U 盘或文件夹（root）/ U 盘或文件夹（没 root） */
interface Source {
    val label: String
    fun manifest(): InputStream
    fun exists(rel: String): Boolean
    /** root 能直接访问的路径；没 root 时为 null */
    fun real(rel: String): String?
    fun open(rel: String): InputStream
}

class RootSource(private val dir: String, override val label: String) : Source {
    override fun manifest() = Root.openRead("$dir/${Store.MANIFEST}")
    override fun exists(rel: String) = Root.exists("$dir/$rel")
    override fun real(rel: String) = "$dir/$rel"
    override fun open(rel: String) = Root.openRead("$dir/$rel")
}

class SafSource(private val ctx: Context, tree: Uri) : Source {
    private val files: Map<String, Uri>
    private val prefix: String
    override val label = "所选文件夹"

    init {
        val all = Saf.walk(ctx, tree)
        // 选的可能是导出目录本身，也可能是它的上一级
        prefix = when {
            all.containsKey(Store.MANIFEST) -> ""
            all.containsKey("${Exporter.DIR_NAME}/${Store.MANIFEST}") -> "${Exporter.DIR_NAME}/"
            else -> throw java.io.IOException("这个文件夹里没有找到备份清单（${Store.MANIFEST}）")
        }
        files = all
    }

    override fun manifest() = open(Store.MANIFEST)
    override fun exists(rel: String) = files.containsKey(prefix + rel)
    override fun real(rel: String): String? = null
    override fun open(rel: String): InputStream =
        ctx.contentResolver.openInputStream(files[prefix + rel] ?: throw java.io.FileNotFoundException(rel))
            ?: throw java.io.FileNotFoundException(rel)
}
