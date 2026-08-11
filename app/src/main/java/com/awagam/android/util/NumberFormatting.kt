// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.util

import java.util.Locale
import kotlin.math.round

// Up to exa, so that the largest `Long` still scales to a single digit and the
// last unit never has to print a four-digit amount
private val UNIT_SUFFIXES = listOf("K", "M", "B", "T", "P", "E")

/**
 * Abbreviate a count for space-constrained display (stat cards, notifications).
 * Values below 1,000 stay exact; larger ones scale to the biggest unit that
 * leaves a whole part, carrying one decimal only below ten so the result stays
 * within four characters—the stat cards fit about that many—for every `Long`.
 * A trailing “.0” is dropped, so 2,000 reads as “2K” rather than “2.0K”.
 *
 * Formatted against `Locale.ROOT`, not the device locale: the separator has to
 * be a dot for `removeSuffix` to match at all—on a device set to German it would
 * be a comma, and 2,000 would read as “2,0K”—and the rest of the interface is
 * English regardless of what the device is set to.
 */
fun formatCompact(value: Long): String {
    if (value < 1_000) return value.toString()

    var scaled = value / 1_000.0
    var unit = 0
    // `round` matches what “%.0f” would print, so this catches the values that
    // only reach the next unit once rounded: 999,900 is 999.9K, that is “1M”
    while (unit < UNIT_SUFFIXES.lastIndex && round(scaled) >= 1_000) {
        scaled /= 1_000
        unit++
    }

    val amount = if (scaled < 10) {
        "%.1f".format(Locale.ROOT, scaled).removeSuffix(".0")
    } else {
        "%.0f".format(Locale.ROOT, scaled)
    }
    return amount + UNIT_SUFFIXES[unit]
}
