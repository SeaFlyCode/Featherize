package com.featherize.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `formatBytes handles zero and negative as 0 o`() {
        assertEquals("0 o", formatBytes(0))
        assertEquals("0 o", formatBytes(-5))
    }

    @Test
    fun `formatBytes stays in bytes below 1024`() {
        assertEquals("512 o", formatBytes(512))
    }

    @Test
    fun `formatBytes switches to kilobytes at 1024`() {
        assertEquals("1.0 Ko", formatBytes(1024))
    }

    @Test
    fun `formatBytes switches to megabytes`() {
        assertEquals("1.9 Mo", formatBytes(2_000_000))
    }

    @Test
    fun `formatSavings reports shrinkage as a negative percent`() {
        assertEquals("-50%", formatSavings(0.5f))
    }

    @Test
    fun `formatSavings reports growth as a positive percent`() {
        assertEquals("+20%", formatSavings(-0.2f))
    }

    @Test
    fun `formatSavings treats zero ratio as no change`() {
        assertEquals("-0%", formatSavings(0f))
    }
}
