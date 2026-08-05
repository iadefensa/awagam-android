// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.util

import java.util.Locale

/**
 * Abbreviate a count for space-constrained display (stat cards, notifications).
 * Values below 1,000 stay exact; larger ones collapse to one decimal, with a
 * trailing “.0” dropped so 2,000 reads as “2K” rather than “2.0K”.
 *
 * Formatted against `Locale.ROOT`, not the device locale: the separator has to
 * be a dot for `removeSuffix` to match at all—on a device set to German it would
 * be a comma, and 2,000 would read as “2,0K”—and the rest of the interface is
 * English regardless of what the device is set to.
 */
fun formatCompact(value: Long): String {
    return when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> {
            val k = value / 1_000.0
            "%.1f".format(Locale.ROOT, k).removeSuffix(".0") + "K"
        }
        else -> {
            val m = value / 1_000_000.0
            "%.1f".format(Locale.ROOT, m).removeSuffix(".0") + "M"
        }
    }
}