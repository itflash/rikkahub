package me.rerere.rikkahub.ui.pages.projects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ProjectThumbnails {
    fun file(context: Context, projectId: String): File = File(context.filesDir, "project_thumbs/$projectId.png")
    fun delete(context: Context, projectId: String) { file(context, projectId).delete() }

    suspend fun capture(webView: WebView, projectId: String) = withContext(Dispatchers.Main.immediate) {
        if (webView.width <= 0 || webView.height <= 0 || webView.progress < 100) return@withContext
        val visible = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(visible)
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            val scaled = Bitmap.createScaledBitmap(visible, 360, (visible.height * 360f / visible.width).toInt().coerceAtLeast(1), true)
            try {
                withContext(Dispatchers.IO) {
                    val target = file(webView.context, projectId)
                    target.parentFile?.mkdirs()
                    val temp = File(target.parentFile, "${target.name}.tmp")
                    try {
                        temp.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        check(temp.renameTo(target))
                    } finally { temp.delete() }
                }
            } finally { if (scaled !== visible) scaled.recycle() }
        } finally { visible.recycle() }
    }
}
