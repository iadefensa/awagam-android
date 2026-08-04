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

// Amber-400 (semantic: warning, matching the extension’s warning status)—
// Material 3 color schemes have no warning slot (contrast with surface ≈ 8:1)
val Warning = Color(0xFFFBBF24)

// Shadcn-style neutral palette (matching extension design)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFEBEBEB),            // Near-white (matching Basecoat primary)
    onPrimary = Color(0xFF000000),          // Black text on primary
    secondary = Color(0xFF333333),          // Dark gray (matching web secondary)
    onSecondary = Color.White,               // White text on secondary
    secondaryContainer = Color(0xFF333333), // Neutral card (countdown)
    onSecondaryContainer = Color(0xFFFBFBFB), // Light text (contrast ≈ 12:1)
    tertiary = Color(0xFF15803D),           // Green-700 (contrast with white ≈ 5:1)
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
 * Switch colors for the main protection toggle, green when on to match its label.
 */
@Composable
fun protectionSwitchColors(): SwitchColors = awagamSwitchColors().copy(
    checkedThumbColor = MaterialTheme.colorScheme.tertiary,
    checkedTrackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
)
