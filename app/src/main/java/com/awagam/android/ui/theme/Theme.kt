package com.awagam.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Shadcn-style neutral palette (matching extension design)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFEBEBEB),           // Light gray (for primary actions)
    onPrimary = Color(0xFF252525),          // Dark text on primary
    secondary = Color(0xFF454545),          // Dark gray
    onSecondary = Color(0xFFFBFBFB),        // Light text on secondary
    tertiary = Color(0xFF16A34A),           // Green (semantic: success/allowed)
    onTertiary = Color.White,
    background = Color(0xFF252525),         // Dark background
    onBackground = Color(0xFFFBFBFB),       // Light text
    surface = Color(0xFF343434),            // Slightly lighter surface
    onSurface = Color(0xFFFBFBFB),          // Light text
    surfaceVariant = Color(0xFF454545),     // For cards/containers
    onSurfaceVariant = Color(0xFFB5B5B5),   // Muted text
    outline = Color(0xFF5E5E5E),            // Borders
    error = Color(0xFFDC2626)               // Red (semantic: blocked/error)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF343434),            // Dark charcoal (for primary actions)
    onPrimary = Color.White,                // White text on primary
    secondary = Color(0xFFF7F7F7),          // Very light gray
    onSecondary = Color(0xFF252525),        // Dark text on secondary
    tertiary = Color(0xFF16A34A),           // Green (semantic: success/allowed)
    onTertiary = Color.White,
    background = Color.White,               // Pure white background
    onBackground = Color(0xFF252525),       // Dark text
    surface = Color.White,                  // White surface
    onSurface = Color(0xFF252525),          // Dark text
    surfaceVariant = Color(0xFFF7F7F7),     // Light gray for cards/containers
    onSurfaceVariant = Color(0xFF8E8E8E),   // Muted text
    outline = Color(0xFFEBEBEB),            // Light gray borders
    error = Color(0xFFDC2626)               // Red (semantic: blocked/error)
)

@Composable
fun AWAGAMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
