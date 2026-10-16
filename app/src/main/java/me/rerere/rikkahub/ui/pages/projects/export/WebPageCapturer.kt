package me.rerere.rikkahub.ui.pages.projects.export

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.pages.projects.ProjectFileServer
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil

class WebPageCapturer(private val activity: Activity) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(server: ProjectFileServer, entryPath: String, outputDir: File, baseName: String, onProgress: (Int, Int) -> Unit): CaptureResult = withContext(Dispatchers.Main.immediate) {
        val density = activity.resources.displayMetrics.density
        val width = (390 * density).toInt()
        val viewport = (844 * density).toInt()
        val parent = activity.window.decorView as ViewGroup
        val container = FrameLayout(activity).apply { alpha = 0f; isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        val webView = WebView(activity)
        val files = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        try {
            parent.addView(container, ViewGroup.LayoutParams(width, viewport))
            container.addView(webView, FrameLayout.LayoutParams(width, viewport))
            webView.apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Ignore <meta viewport> so the layout is always exactly 390 CSS px; pages without one would
                // otherwise lay out at 980 px and break the CSS px -> view px mapping used for cropping.
                settings.useWideViewPort = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isEnabled = false
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            fun layout(height: Int) {
                webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                webView.layout(0, 0, width, height)
            }
            layout(viewport)
            val loaded = CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = server.intercept(request.url)
                override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
            }
            webView.loadUrl(server.urlOf(entryPath))
            if (withTimeoutOrNull(15_000) { loaded.await(); true } == null) warnings.add("页面加载超时，截图可能不完整")
            webView.js("window.__projectFontsReady=false; Promise.resolve(document.fonts ? document.fonts.ready : null).then(()=>window.__projectFontsReady=true); true")
            withTimeoutOrNull(3_000) { while (webView.js("window.__projectFontsReady") != "true") delay(100) }
            for (step in 0 until 50) {
                val bottom = webView.js("(scrollY+innerHeight>=Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0)-2)") == "true"
                if (bottom) break
                webView.js("scrollBy(0,innerHeight);true")
                delay(150)
            }
            webView.js("scrollTo(0,0);true"); delay(800)
            val metrics = Json.parseToJsonElement(webView.js(METRICS)).jsonObject
            val pageH = metrics.getValue("pageH").jsonPrimitive.double.coerceAtLeast(1.0)
            val vh = metrics.getValue("vh").jsonPrimitive.double.coerceAtLeast(1.0)
            val slides = metrics.getValue("slides").jsonArray
            val locked = metrics.getValue("locked").jsonPrimitive.boolean
            val plan = planCapture(pageH, vh, slides.map {
                CaptureSegment(it.jsonObject.getValue("top").jsonPrimitive.double, it.jsonObject.getValue("height").jsonPrimitive.double)
            }, locked)
            val mode = plan.mode
            val segments = plan.segments
            warnings.addAll(plan.warnings)
            if (mode == CaptureMode.SINGLE || mode == CaptureMode.SPLIT) {
                // Growing the view to the page height also grows the viewport, so 100vh heroes would stretch to
                // the whole page. Pin viewport-relative sizes to their current px values first.
                webView.js(FREEZE_VIEWPORT_UNITS)
                layout(ceil(minOf(pageH, segments.last().let { it.top + it.height }) * density).toInt())
                // Two display frames after relayout, so Chromium has a chance to update its display list.
                webView.frame(); webView.frame()
            }
            val selected = segments
            for ((index, segment) in selected.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (top, height) = segment
                val screenshotHeight = if (mode == CaptureMode.SLIDES || mode == CaptureMode.VIEWPORT_ONLY) vh else height
                if (mode == CaptureMode.SLIDES) { webView.js("scrollTo(0,$top);true"); delay(300) }
                val scale = if (screenshotHeight * 3 > 16_384) 2 else 3
                val bitmap = Bitmap.createBitmap(390 * scale, ceil(screenshotHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.scale(scale / density, scale / density)
                    if (mode == CaptureMode.SINGLE || mode == CaptureMode.SPLIT) {
                        canvas.translate(0f, (-top * density).toFloat())
                    } else {
                        // View.draw expects its parent to apply the scroll offset; without this every slide repeats page one.
                        canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
                    }
                    webView.draw(canvas)
                    val file = File(outputDir, if (selected.size == 1) "$baseName.png" else "$baseName-${index + 1}.png")
                    files.add(file)
                    withContext(Dispatchers.IO) {
                        outputDir.mkdirs()
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图写入失败" } }
                    }
                } finally { bitmap.recycle() }
                onProgress(index + 1, selected.size)
            }
            CaptureResult(files.toList(), mode, warnings)
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
            throw e
        } finally {
            webView.stopLoading()
            container.removeView(webView)
            parent.removeView(container)
            webView.destroy()
        }
    }

    private suspend fun WebView.js(script: String): String = suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { value ->
            if (continuation.isActive) {
                val decoded = runCatching { Json.parseToJsonElement(value) }.getOrNull()
                continuation.resume(if (decoded is JsonPrimitive) decoded.content else value)
            }
        }
    }
    private suspend fun WebView.frame(): Unit = suspendCancellableCoroutine { continuation -> postOnAnimation { if (continuation.isActive) continuation.resume(Unit) } }

    private companion object {
        val METRICS = """
            (() => {
              const vh=innerHeight,de=document.documentElement,body=document.body;
              const pageH=Math.max(de.scrollHeight,body?body.scrollHeight:0);
              const snap=[de,body].some(e=>e&&/(y|both|block)/.test(getComputedStyle(e).scrollSnapType));
              let slides=[];
              for(const c of [body,document.querySelector('main')].filter(Boolean)) {
                const kids=[...c.children].filter(k=>Math.abs(k.getBoundingClientRect().height-vh)<=vh*.05);
                if(kids.length>=2&&kids.reduce((s,k)=>s+k.getBoundingClientRect().height,0)>=pageH*.8){slides=kids;break;}
              }
              if(snap&&!slides.length&&body)slides=[...body.children].filter(k=>k.getBoundingClientRect().height>=vh*.9);
              const rects=slides.map(k=>{const r=k.getBoundingClientRect();return {top:r.top+scrollY,height:r.height};});
              const locked=pageH<=vh+2&&[de,body].some(e=>e&&getComputedStyle(e).overflowY==='hidden');
              return JSON.stringify({pageH,vh,slides:rects,locked});
            })()
        """.trimIndent()

        /** Pins vh/vmax-based sizes (and html/body percentage heights) to px; returns the number pinned. */
        val FREEZE_VIEWPORT_UNITS = """
            (() => {
              const props=['height','min-height','max-height','top','bottom','padding-top','padding-bottom','margin-top','margin-bottom','font-size','line-height'];
              const re=/[\d.](?:[sld]?vh|vmax|vb)\b/i;
              const targets=new Map();
              const add=(el,p)=>{if(!targets.has(el))targets.set(el,new Set());targets.get(el).add(p);};
              const walk=rules=>{for(const r of rules){
                if(r.style&&r.selectorText){
                  const ps=props.filter(p=>re.test(r.style.getPropertyValue(p)));
                  if(ps.length){try{document.querySelectorAll(r.selectorText).forEach(el=>ps.forEach(p=>add(el,p)));}catch(e){}}
                }
                if(r.cssRules)walk(r.cssRules);
              }};
              for(const s of document.styleSheets){try{walk(s.cssRules);}catch(e){}}
              document.querySelectorAll('[style]').forEach(el=>props.forEach(p=>{if(re.test(el.style.getPropertyValue(p)))add(el,p);}));
              // html/body percentage heights resolve against the viewport too; pinning their current size is a no-op otherwise.
              [document.documentElement,document.body].filter(Boolean).forEach(el=>{add(el,'height');add(el,'min-height');});
              const pinned=[];
              targets.forEach((ps,el)=>{const cs=getComputedStyle(el);ps.forEach(p=>pinned.push([el,p,cs.getPropertyValue(p)]));});
              pinned.forEach(([el,p,v])=>el.style.setProperty(p,v,'important'));
              return pinned.length;
            })()
        """.trimIndent()
    }
}

data class CaptureResult(val files: List<File>, val mode: CaptureMode, val warnings: List<String>)
