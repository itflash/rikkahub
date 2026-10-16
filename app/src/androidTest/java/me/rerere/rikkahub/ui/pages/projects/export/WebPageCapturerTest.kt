package me.rerere.rikkahub.ui.pages.projects.export

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.pages.projects.ProjectFileServer
import me.rerere.ui.webview.WebContent
import me.rerere.ui.webview.WebViewState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File

/** Software Canvas drawing needs an explicit scroll offset; JVM planning tests cannot catch this regression. */
@RunWith(AndroidJUnit4::class)
class WebPageCapturerTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun capturesEachSlideRatherThanRepeatingTheFirstPage() = runBlocking {
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            lateinit var activity: RouteActivity
            scenario.onActivity { activity = it }
            val root = File(activity.cacheDir, "capture-test-${System.nanoTime()}").apply { mkdirs() }
            try {
                val colors = listOf("#e9ecff", "#d1f4dc", "#ffdfc6", "#d9eeff", "#f6d6e9")
                File(root, "index.html").writeText("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}section{height:100vh}</style>""" +
                    colors.joinToString("") { "<section style=\"background:$it\"></section>" })
                val result = WebPageCapturer(activity).capture(ProjectFileServer(root), "index.html", File(root, "out"), "slides") { _, _ -> }
                assertEquals(CaptureMode.SLIDES, result.mode)
                assertEquals(5, result.files.size)
                result.files.forEachIndexed { index, file ->
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    try {
                        assertEquals(1170, bitmap.width)
                        assertEquals(2532, bitmap.height)
                        // Avoid the subpixel anti-aliased seam at the viewport edge on fractional-density devices.
                        assertEquals("Slide ${index + 1}", Color.parseColor(colors[index]), bitmap.getPixel(30, 30))
                    } finally { bitmap.recycle() }
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun longPageKeepsViewportHeightHeroAndFooter() = runBlocking {
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            lateinit var activity: RouteActivity
            scenario.onActivity { activity = it }
            val root = File(activity.cacheDir, "capture-test-${System.nanoTime()}").apply { mkdirs() }
            try {
                File(root, "index.html").writeText("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%}.hero{min-height:100vh;background:#2b59ff}.body{height:2000px;background:#22aa44}footer{height:200px;background:#ee2222}</style><div class="hero"></div><div class="body"></div><footer></footer>""")
                val result = WebPageCapturer(activity).capture(ProjectFileServer(root), "index.html", File(root, "out"), "hero") { _, _ -> }
                assertEquals(CaptureMode.SINGLE, result.mode)
                val bitmap = BitmapFactory.decodeFile(result.files.single().absolutePath)
                try {
                    // Hero must stay one viewport tall instead of stretching to the relaid-out view height.
                    assertTrue("height ${bitmap.height}", bitmap.height in 3 * 3000..3 * 3100)
                    assertEquals(Color.parseColor("#2b59ff"), bitmap.getPixel(30, 30))
                    assertEquals(Color.parseColor("#22aa44"), bitmap.getPixel(30, 3 * 900))
                    assertEquals(Color.parseColor("#ee2222"), bitmap.getPixel(30, bitmap.height - 30))
                } finally { bitmap.recycle() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun overlayBasedDeckCapturesOnlyOneViewport() = runBlocking {
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            lateinit var activity: RouteActivity
            scenario.onActivity { activity = it }
            val root = File(activity.cacheDir, "capture-test-${System.nanoTime()}").apply { mkdirs() }
            try {
                File(root, "index.html").writeText("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100vh;overflow:hidden}section{position:absolute;inset:0;height:100vh;background:#e9ecff}section:not(:first-child){opacity:0}</style><section>1</section><section>2</section><section>3</section>""")
                val result = WebPageCapturer(activity).capture(ProjectFileServer(root), "index.html", File(root, "out"), "overlay") { _, _ -> }
                assertEquals(CaptureMode.VIEWPORT_ONLY, result.mode)
                assertEquals(1, result.files.size)
                assertTrue(result.warnings.any { it.contains("只能截取当前屏") })
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun entryAndProjectChangesRebindConsoleAndRequestInterceptors() = runBlocking {
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            lateinit var activity: RouteActivity
            scenario.onActivity { activity = it }
            val root = File(activity.cacheDir, "capture-test-${System.nanoTime()}").apply { mkdirs() }
            try {
                File(root, "index.html").writeText("<!doctype html><p>first</p>")
                File(root, "error.html").writeText("<!doctype html><script>throw new Error('entry switch')</script>")
                val other = File(root, "other").apply { mkdirs() }
                File(other, "error.html").writeText("<!doctype html><script>throw new Error('other project')</script>")
                val server = ProjectFileServer(root)
                val otherServer = ProjectFileServer(other)
                val interceptor = { uri: android.net.Uri -> server.intercept(uri) }
                val first = WebViewState(WebContent.Url(server.urlOf("index.html")), requestInterceptor = interceptor)
                val second = WebViewState(WebContent.Url(server.urlOf("error.html")), requestInterceptor = interceptor)
                val third = WebViewState(WebContent.Url(otherServer.urlOf("error.html")), requestInterceptor = otherServer::intercept)
                val selected = mutableStateOf(first)
                scenario.onActivity { it.setContent { me.rerere.ui.webview.WebView(selected.value, Modifier.fillMaxSize()) } }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { first.currentUrl == server.urlOf("index.html") && !first.isLoading }) delay(50)
                }
                withContext(Dispatchers.Main) { selected.value = second }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { second.consoleMessages.any { it.message().contains("entry switch") } }) delay(50)
                }
                // Both projects use exactly the same virtual URL. A different interceptor must still force a load.
                withContext(Dispatchers.Main) { selected.value = third }
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { third.consoleMessages.any { it.message().contains("other project") } }) delay(50)
                }
                assertTrue(withContext(Dispatchers.Main) { first.consoleMessages.none { it.message().contains("entry switch") } })
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationRemovesTheOffscreenViewAndPartialFiles() = runBlocking {
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            lateinit var activity: RouteActivity
            scenario.onActivity { activity = it }
            val root = File(activity.cacheDir, "capture-test-${System.nanoTime()}").apply { mkdirs() }
            try {
                File(root, "index.html").writeText("""<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;height:40000px">cancel</body>""")
                val parent = withContext(Dispatchers.Main) { activity.window.decorView as ViewGroup }
                val before = withContext(Dispatchers.Main) { parent.childCount }
                val output = File(root, "out")
                val job = launch { WebPageCapturer(activity).capture(ProjectFileServer(root), "index.html", output, "cancel") { _, _ -> } }
                delay(1_000)
                job.cancelAndJoin()
                assertEquals(before, withContext(Dispatchers.Main) { parent.childCount })
                assertTrue(output.listFiles().isNullOrEmpty())
            } finally { root.deleteRecursively() }
        }
    }
}
