// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import com.awagam.android.util.formatCompact
import java.util.Locale

/**
 * Unit tests for compact count formatting.
 * The locale cases are the point: the device locale decides the decimal
 * separator, and a comma would keep `removeSuffix(".0")` from ever matching.
 */
class NumberFormattingTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `values below one thousand stay exact`() {
        assertEquals("0", formatCompact(0))
        assertEquals("999", formatCompact(999))
    }

    @Test
    fun `thousands and millions abbreviate without a trailing zero`() {
        assertEquals("1K", formatCompact(1_000))
        assertEquals("1.2K", formatCompact(1_200))
        assertEquals("2K", formatCompact(2_000))
        assertEquals("150K", formatCompact(150_000))
        assertEquals("1M", formatCompact(1_000_000))
        assertEquals("1.4M", formatCompact(1_400_000))
    }

    @Test
    fun `a comma-decimal locale still formats with a dot`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.2K", formatCompact(1_200))
        assertEquals("1.4M", formatCompact(1_400_000))
    }

    @Test
    fun `a comma-decimal locale still drops the trailing zero`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("2K", formatCompact(2_000))
        assertEquals("1M", formatCompact(1_000_000))
    }
}