package app.datame.movil

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId

/** A file of the phone that may have to go to the house. */
data class LocalFile(
    val key: String, // stable: the same file keeps it while it exists
    val uri: Uri,
    val name: String,
    val size: Long,
    val modifiedSeconds: Long,
    val mime: String?,
    val remoteDir: String, // inside the folder of the phone: «Camera/2026», «Documentos/facturas»
    val media: Boolean,
)

/** A folder of photos of the phone, as the gallery shows it. */
data class Bucket(val id: String, val name: String, val count: Int)

object Sources {

    fun hasMediaPermission(context: Context): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    private val mediaCollections by lazy {
        listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
    }

    /** The folders of photos and videos, with how many each has. */
    fun buckets(context: Context): List<Bucket> {
        if (!hasMediaPermission(context)) return emptyList()
        val found = linkedMapOf<String, Bucket>()
        for (collection in mediaCollections) {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: "Sin nombre"
                    val previous = found[id]
                    found[id] = Bucket(id, name, (previous?.count ?: 0) + 1)
                }
            }
        }
        return found.values.sortedWith(compareByDescending<Bucket> { it.name.equals("Camera", true) }.thenByDescending { it.count })
    }

    /** The photos and videos of the chosen folders, the newest first. */
    fun media(context: Context, config: Config): List<LocalFile> {
        if (!config.photos || !hasMediaPermission(context)) return emptyList()
        if (!config.allBuckets && config.buckets.isEmpty()) return emptyList()
        val out = mutableListOf<LocalFile>()
        val zone = ZoneId.systemDefault()
        for (collection in mediaCollections) {
            val selection = StringBuilder("${MediaStore.MediaColumns.IS_PENDING} = 0")
            val args = mutableListOf<String>()
            if (!config.allBuckets) {
                selection.append(" AND ${MediaStore.MediaColumns.BUCKET_ID} IN (${config.buckets.joinToString(",") { "?" }})")
                args += config.buckets
            }
            if (config.sinceMillis > 0) {
                selection.append(" AND ${MediaStore.MediaColumns.DATE_ADDED} >= ?")
                args += (config.sinceMillis / 1000).toString()
            }
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                ),
                selection.toString(), args.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucket = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val fileSize = cursor.getLong(size)
                    val fileName = cursor.getString(name) ?: continue
                    if (fileSize <= 0) continue
                    val modifiedSeconds = cursor.getLong(modified)
                    // A folder per year, by when it was taken: years of photos in one folder are hard to browse.
                    val when_ = cursor.getLong(taken).takeIf { it > 0 } ?: (modifiedSeconds * 1000)
                    val year = Instant.ofEpochMilli(when_).atZone(zone).year
                    var uri = ContentUris.withAppendedId(collection, cursor.getLong(id))
                    // The original, with where it was taken, when the person allowed it.
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        uri = MediaStore.setRequireOriginal(uri)
                    }
                    out += LocalFile(
                        key = "ms:" + cursor.getLong(id), // ids are unique among all the media of the phone
                        uri = uri,
                        name = fileName,
                        size = fileSize,
                        modifiedSeconds = modifiedSeconds,
                        mime = cursor.getString(mime),
                        remoteDir = safeName(cursor.getString(bucket) ?: "Fotos") + "/" + year,
                        media = true,
                    )
                }
            }
        }
        return out
    }

    /** The files of a folder chosen with the picker, and of its folders. */
    fun tree(context: Context, folder: TreeFolder, config: Config): List<LocalFile> {
        val treeUri = Uri.parse(folder.uri)
        val out = mutableListOf<LocalFile>()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return out
        }
        // Breadth first, with the path of each folder: «facturas/2026».
        val pending = ArrayDeque(listOf(rootId to ""))
        var visited = 0
        while (pending.isNotEmpty() && visited < 20000) {
            val (documentId, relative) = pending.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            try {
                context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        if (name.startsWith(".")) continue // hidden: thumbnails, caches, trash
                        val mime = cursor.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pending.addLast(id to (if (relative.isEmpty()) safeName(name) else relative + "/" + safeName(name)))
                            continue
                        }
                        val size = cursor.getLong(3)
                        val modifiedMillis = cursor.getLong(4)
                        if (size <= 0) continue
                        if (config.sinceMillis > 0 && modifiedMillis < config.sinceMillis) continue
                        out += LocalFile(
                            key = "tree:" + folder.uri + ":" + id,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name = name,
                            size = size,
                            modifiedSeconds = modifiedMillis / 1000,
                            mime = mime,
                            remoteDir = safeName(folder.label) + (if (relative.isEmpty()) "" else "/$relative"),
                            media = false,
                        )
                    }
                }
            } catch (e: SecurityException) {
                return out // the permission for that folder was taken away
            }
        }
        return out
    }

    /** Names that are good folder names in the house. */
    fun safeName(name: String): String = name.replace('/', '-').replace(Regex("[\\u0000-\\u001f]"), "").trim().ifBlank { "Sin nombre" }
}
