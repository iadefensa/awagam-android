// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Amber-400 (semantic: warning, matching the AWAGAM extension’s warning
// status)—Material 3 color schemes have no warning slot (contrast with
// surface ≈ 8:1)
val Warning = Color(0xFFFBBF24)

// Amber-900, the container step for `[Warning]`, matching how the green and red
// containers pair with their foreground colors (contrast with light text ≈ 8.5:1)
val WarningContainer = Color(0xFF78350F)

// The iadefensa.com accent, and the app’s one bright color. It marks whatever
// matters most on a screen, following the site, where lime is the primary
// action: on Home the protection state (status line, card tint, toggle), in
// settings the button that adds a blocklist.
val Brand = Color(0xFFCEFF1A)

// Black on lime, as on the site’s primary buttons (≈ 17.9:1)
val OnBrand = Color(0xFF000000)

// Red-300 (semantic: error), the same idea for red. `error` is red-600, sized so
// white reads on it as a fill; against the `surfaceVariant` cards that carry the
// blocklist errors, the delete action, and the block-rate bar it is only ≈ 2.2:1.
// Red-400 still falls short for text, hence the lighter step (≈ 5.5:1).
val ErrorText = Color(0xFFFCA5A5)

// Shadcn-style neutral palette (matching AWAGAM extension design)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFEBEBEB),            // Near-white (matching Basecoat primary)
    onPrimary = Color(0xFF000000),          // Black text on primary
    // Same pair again, for the components that reach for the container slots—
    // the settings FAB above all. Left unset, they fall back to Material’s
    // baseline purple, which is both off-palette and only ≈ 1.9:1 against the
    // background, short of the 3:1 a control has to clear.
    primaryContainer = Color(0xFFEBEBEB),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF333333),          // Dark gray (matching web secondary)
    onSecondary = Color.White,               // White text on secondary
    secondaryContainer = Color(0xFF333333), // Tonal buttons (the disable durations)
    onSecondaryContainer = Color(0xFFFBFBFB), // Light text (contrast ≈ 12:1)
    // Green-700 (contrast with white ≈ 5:1). No longer drawn directly—[Brand]
    // carries the protection-active signal now—but kept as the accent the
    // container below pairs with, and for any Material component reaching for it.
    tertiary = Color(0xFF15803D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF14532D),  // Green-900 card (empty state, battery prompt)
    onTertiaryContainer = Color(0xFFFBFBFB), // Light text (contrast ≈ 9:1)
    background = Color(0xFF18181B),         // Dark background (zinc-900)
    onBackground = Color(0xFFFBFBFB),       // Light text
    surface = Color(0xFF27272A),            // Slightly lighter surface (zinc-800)
    onSurface = Color(0xFFFBFBFB),          // Light text
    surfaceVariant = Color(0xFF3F3F46),     // For cards/containers (zinc-700)
    onSurfaceVariant = Color(0xFFB5B5B5),   // Muted text
    outline = Color(0xFF333333),            // Borders
    error = Color(0xFFDC2626),              // Red (semantic: blocked/error)
    errorContainer = Color(0xFF7F1D1D),     // Red-900 card (VPN errors)
    onErrorContainer = Color(0xFFFBFBFB)    // Light text (contrast ≈ 9.5:1)
)

@Composable
fun AWAGAMTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Switch colors for every toggle in the app.
 * Only the unchecked state is overridden: The Material default draws thumb and
 * track in the colors of the card behind them.
 */
@Composable
fun awagamSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * Switch colors for the main protection toggle, carrying [Brand] when on to
 * match its label. A filled track with a dark thumb, rather than the tinted
 * track and matching thumb of before: those differed by only 2.85:1 from each
 * other, so the control read as one shape whichever way it was set.
 */
@Composable
fun protectionSwitchColors(): SwitchColors = awagamSwitchColors().copy(
    checkedThumbColor = OnBrand,
    checkedTrackColor = Brand
)
