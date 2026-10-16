package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.ui.pages.projects.export.*
import org.junit.Assert.*
import org.junit.Test

class CapturePlanTest {
    @Test fun `ordinary short and long pages follow size thresholds`() {
        assertEquals(CaptureMode.SINGLE, planCapture(3000.0, 844.0, emptyList(), false).mode)
        val split = planCapture(15000.0, 844.0, emptyList(), false)
        assertEquals(CaptureMode.SPLIT, split.mode)
        assertEquals(listOf(4000.0, 4000.0, 4000.0, 3000.0), split.segments.map { it.height })
    }
    @Test fun `slides and locked viewport are recognized`() {
        val slides = List(5) { CaptureSegment(it * 844.0, 844.0) }
        assertEquals(CaptureMode.SLIDES, planCapture(4220.0, 844.0, slides, false).mode)
        val locked = planCapture(844.0, 844.0, emptyList(), true)
        assertEquals(CaptureMode.VIEWPORT_ONLY, locked.mode)
        assertEquals(1, locked.warnings.size)
    }
    @Test fun `overlaid JavaScript slides export only the visible viewport`() {
        val overlays = List(5) { CaptureSegment(0.0, 844.0) }
        assertEquals(CaptureMode.VIEWPORT_ONLY, planCapture(844.0, 844.0, overlays, true).mode)
    }
    @Test fun `only nine images are planned even for pathological page heights`() {
        val plan = planCapture(1e12, 844.0, emptyList(), false)
        assertEquals(9, plan.segments.size)
        assertTrue(plan.warnings.single().contains("已保留前 9 张"))
    }
}
