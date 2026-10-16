package me.rerere.rikkahub.ui.pages.projects.export

import kotlin.math.ceil

enum class CaptureMode { SINGLE, SPLIT, SLIDES, VIEWPORT_ONLY }
internal data class CaptureSegment(val top: Double, val height: Double)
internal data class CapturePlan(val mode: CaptureMode, val segments: List<CaptureSegment>, val warnings: List<String>)

/** Plan in CSS pixels before allocating any bitmaps; even pathological pages allocate at most nine segments. */
internal fun planCapture(pageHeight: Double, viewportHeight: Double, slides: List<CaptureSegment>, locked: Boolean): CapturePlan {
    require(pageHeight.isFinite() && viewportHeight.isFinite() && pageHeight > 0 && viewportHeight > 0) { "页面尺寸无效" }
    val mode: CaptureMode
    val segments: List<CaptureSegment>
    val total: Long
    val warnings = mutableListOf<String>()
    // JS decks often overlay several viewport-sized sections at the same top; these are not scrollable slides.
    val stackedSlides = slides.size >= 2 && pageHeight > viewportHeight + 2 &&
        slides.zipWithNext().all { (first, next) -> next.top >= first.top + first.height * 0.9 }
    when {
        stackedSlides -> {
            mode = CaptureMode.SLIDES
            total = slides.size.toLong()
            segments = slides.take(9).map { CaptureSegment(it.top.coerceAtLeast(0.0), viewportHeight) }
        }
        locked && pageHeight <= viewportHeight + 2 -> {
            mode = CaptureMode.VIEWPORT_ONLY; total = 1; segments = listOf(CaptureSegment(0.0, viewportHeight))
            warnings.add("该页面只能截取当前屏（可能是用脚本翻页的幻灯片）")
        }
        pageHeight <= 6_000 -> { mode = CaptureMode.SINGLE; total = 1; segments = listOf(CaptureSegment(0.0, pageHeight)) }
        else -> {
            mode = CaptureMode.SPLIT; total = ceil(pageHeight / 4_000).toLong()
            segments = List(minOf(total, 9).toInt()) { CaptureSegment(it * 4_000.0, minOf(4_000.0, pageHeight - it * 4_000.0)) }
        }
    }
    if (total > 9) warnings.add("共 $total 屏，微信一次最多发 9 张，已保留前 9 张")
    return CapturePlan(mode, segments, warnings)
}
