// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.util

/**
 * Abbreviate a count for space-constrained display (stat cards, notifications).
 * Values below 1,000 stay exact; larger ones collapse to one decimal, with a
 * trailing “.0” dropped so 2,000 reads as “2K” rather than “2.0K”.
 */
fun formatCompact(value: Long): String {
    return when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> {
            val k = value / 1_000.0
            "%.1f".format(k).removeSuffix(".0") + "K"
        }
        else -> {
            val m = value / 1_000_000.0
            "%.1f".format(m).removeSuffix(".0") + "M"
        }
    }
}