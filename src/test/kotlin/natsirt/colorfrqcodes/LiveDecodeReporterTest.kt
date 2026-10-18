package org.example.net.natsirt.colorfrqcodes

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveDecodeReporterTest {
    @Test
    fun `local camera preview targets at least thirty frames per second`() {
        assertEquals(30, CameraStreamViewer.TARGET_FPS)
        assertEquals(33, CameraStreamViewer.PREVIEW_INTERVAL_MS)
    }

    @Test
    fun `prints newly decoded text once until code disappears`() {
        val printed = mutableListOf<String>()
        val reporter = CameraStreamViewer.LiveDecodeReporter(printed::add)

        reporter.report("payload")
        reporter.report("payload")
        reporter.report(null)
        reporter.report("payload")

        assertEquals(listOf("payload", "payload"), printed)
    }

    @Test
    fun `prints changed payload immediately`() {
        val printed = mutableListOf<String>()
        val reporter = CameraStreamViewer.LiveDecodeReporter(printed::add)

        reporter.report("first")
        reporter.report("second")

        assertEquals(listOf("first", "second"), printed)
    }

    @Test
    fun `reports repeated identical camera failure once with its reason`() {
        val printed = mutableListOf<String>()
        val reporter = CameraStreamViewer.LiveDecodeReporter(printed::add)

        reporter.reportFailure("No code-like foreground found.")
        reporter.reportFailure("No code-like foreground found.")

        assertEquals(listOf("Decode not found: No code-like foreground found."), printed)
    }

    @Test
    fun `reports a payload again after camera loses and reacquires it`() {
        val printed = mutableListOf<String>()
        val reporter = CameraStreamViewer.LiveDecodeReporter(printed::add)

        reporter.report("payload")
        reporter.reportFailure("No code-like foreground found.")
        reporter.report("payload")

        assertEquals(
            listOf("payload", "Decode not found: No code-like foreground found.", "payload"),
            printed
        )
    }
}