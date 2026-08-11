// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.awagam.android.util.formatCompact
import java.util.Locale

/**
 * Unit tests for compact count formatting.
 * The locale cases are the point: the device locale decides the decimal
 * separator, and a comma would keep `removeSuffix(".0")` from ever matching.
 * The width case comes second: the stat cards fit about four characters.
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
    fun `the decimal is dropped from ten up`() {
        assertEquals("9.9K", formatCompact(9_900))
        assertEquals("10K", formatCompact(9_960))
        assertEquals("13K", formatCompact(12_500))
        assertEquals("999K", formatCompact(999_400))
        assertEquals("9.9M", formatCompact(9_900_000))
        assertEquals("12M", formatCompact(12_000_000))
    }

    @Test
    fun `rounding that reaches the next unit carries into it`() {
        assertEquals("1M", formatCompact(999_900))
        assertEquals("1M", formatCompact(999_500))
        assertEquals("1B", formatCompact(999_900_000))
    }

    @Test
    fun `billions and trillions abbreviate too`() {
        assertEquals("1B", formatCompact(1_000_000_000))
        assertEquals("2.5B", formatCompact(2_500_000_000))
        assertEquals("40B", formatCompact(40_000_000_000))
        assertEquals("1T", formatCompact(1_000_000_000_000))
        assertEquals("3.2T", formatCompact(3_200_000_000_000))
    }

    @Test
    fun `rounding at the largest unit does not widen the amount`() {
        // The last unit has no next one to carry into, so it has to reach the
        // one below with room to spare: 999.5T would otherwise print “1000T”
        assertEquals("1P", formatCompact(999_500_000_000_000))
        assertEquals("999T", formatCompact(999_400_000_000_000))
        assertEquals("9.2E", formatCompact(Long.MAX_VALUE))
    }

    @Test
    fun `no value the cards can show exceeds four characters`() {
        // Sampled across the whole range rather than at the boundaries alone:
        // the cap is a property of the output, not of a handful of cases.
        // Stops where the multiplication would overflow; the cases above cover
        // the rest of the way to `Long.MAX_VALUE`.
        var magnitude = 1L
        while (magnitude <= 1_000_000_000_000_000L) {
            for (multiplier in 1..999) {
                val value = magnitude * multiplier
                val formatted = formatCompact(value)
                assertTrue(
                    "formatCompact($value) = “$formatted” is longer than four characters",
                    formatted.length <= 4
                )
            }
            magnitude *= 10
        }
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