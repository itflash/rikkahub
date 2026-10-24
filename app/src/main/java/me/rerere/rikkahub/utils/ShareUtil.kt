package me.rerere.rikkahub.utils

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

fun shareFiles(context: Context, files: List<File>, mimeType: String) {
    require(files.isNotEmpty() && files.all { it.isFile }) { "没有可分享的文件" }
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(files.first().name, uris.first()).also { data -> uris.drop(1).forEach { data.addItem(ClipData.Item(it)) } }
    }
    context.startActivity(Intent.createChooser(intent, null).apply { if (context.getActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
}

suspend fun saveToDownloads(context: Context, file: File, mimeType: String, subDir: String = "itflash-rikkahub"): Uri? =
    savePublicFile(context, file, mimeType, Environment.DIRECTORY_DOWNLOADS, subDir, image = false)

suspend fun saveImagesToGallery(context: Context, files: List<File>) {
    for (file in files) savePublicFile(context, file, "image/png", Environment.DIRECTORY_PICTURES, "itflash-rikkahub", image = true)
}

private suspend fun savePublicFile(context: Context, file: File, mimeType: String, directory: String, subDir: String, image: Boolean): Uri = withContext(Dispatchers.IO) {
    require(subDir.isNotBlank() && subDir !in setOf(".", "..") && '/' !in subDir && '\\' !in subDir) { "保存目录无效" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$subDir")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error("无法创建保存文件")
        try {
            val output = resolver.openOutputStream(uri) ?: error("无法写入文件")
            output.use { target -> file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    target.write(buffer, 0, n)
                }
            } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) { "无法完成保存" }
            uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    } else {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) { context.getActivity()?.let { ActivityCompat.requestPermissions(it, arrayOf(permission), 1) } }
            error("请授予存储权限后重试")
        }
        val folder = File(Environment.getExternalStoragePublicDirectory(directory), subDir).apply { mkdirs() }
        var target = File(folder, file.name)
        var suffix = 2
        while (target.exists()) target = File(folder, "${file.nameWithoutExtension}-${suffix++}.${file.extension}")
        try {
            file.copyTo(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            Uri.fromFile(target)
        } catch (e: Exception) { target.delete(); throw e }
    }
}
