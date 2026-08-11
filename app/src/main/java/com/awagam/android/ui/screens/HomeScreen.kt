// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import java.text.NumberFormat
import com.awagam.android.R
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.ui.theme.Brand
import com.awagam.android.ui.theme.WarningContainer
import com.awagam.android.ui.theme.protectionSwitchColors
import com.awagam.android.ui.viewmodel.HomeViewModel
import com.awagam.android.util.formatCompact

// Shared by the stat cards and by the measurement that sizes their counts
private val STAT_CARD_GAP = 12.dp
private val STAT_CARD_PADDING = 16.dp
private val STAT_VALUE_MIN_FONT_SIZE = 14.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onToggleVpn: (Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Restart VPN when temporary disable timer expires.
    // The try-catch handles Android 12+ `ForegroundServiceStartNotAllowedException`
    // when the timer expires while the app is backgrounded without battery exemption.
    // In that case, `onResume()` in MainActivity will retry from a foreground context.
    LaunchedEffect(Unit) {
        viewModel.restartVpnEvent.collect {
            try {
                onToggleVpn(true)
            } catch (_: Exception) {
                // Swallow—`onResume` will retry from a foreground context
            }
        }
    }

    // Check battery optimization status on each resume
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBatteryOptimizationNeeded by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimizationNeeded = !pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The launcher label stays “AWAGAM” (`app_name`), where space
                    // is tight; in the app there is usually room to name the maker.
                    // “Usually”: The full name needs about 208 dp, which a narrow
                    // screen or a raised font scale can undercut. Rather than pick
                    // a dp breakpoint—font scale and display size move that line
                    // independently—let the layout report the overflow and drop to
                    // the short name, which fits at any size. Keyed to the
                    // configuration so a rotation or a settings change re-measures.
                    val configuration = LocalConfiguration.current
                    var useShortTitle by remember(configuration) { mutableStateOf(false) }
                    Text(
                        text = if (useShortTitle) "AWAGAM" else "IA Defensa AWAGAM",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        // Only ever set, never cleared, so the two names cannot
                        // oscillate; the ellipsis is a backstop for the short one
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (!useShortTitle && result.hasVisualOverflow) useShortTitle = true
                        }
                    )
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToStatistics,
                        modifier = Modifier.semantics {
                            contentDescription = "View statistics"
                        }
                    ) {
                        Icon(
                            Icons.Filled.BarChart,
                            contentDescription = "Statistics"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.semantics {
                            contentDescription = "Open settings"
                        }
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Card, title, and switch move together; the wording carries the
            // difference between a start in progress and a confirmed one
            val isActive = uiState.isEnabled || uiState.isStarting

            // Main toggle card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) {
                        Brand.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Makes the whole card the target the “Tap to enable” copy
                        // promises, and pairs the label with the switch for TalkBack
                        .toggleable(
                            value = isActive,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                // The switch follows the preference the VPN service writes,
                                // so enabling only shows a pending state here; the service
                                // confirms it, and `startRequested` gives up if it doesn’t
                                if (enabled) {
                                    viewModel.startRequested()
                                } else {
                                    viewModel.setEnabled(false)
                                }
                                onToggleVpn(enabled)
                            }
                        )
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                uiState.isEnabled -> "Protection Active"
                                uiState.isStarting -> "Starting Protection"
                                else -> "Protection Off"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isActive) {
                                // `Brand`, not a scheme color: the one place
                                // the app shows its accent
                                Brand
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (uiState.isEnabled) {
                                "DNS filtering is enabled"
                            } else if (uiState.isStarting) {
                                "Waiting for the VPN tunnel…"
                            } else if (uiState.isTemporarilyDisabled) {
                                "Temporarily disabled"
                            } else {
                                "Tap to enable DNS filtering"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Same pending affordance as the blocklist refresh button.
                    // Unlike that one the control stays enabled: A toggle that
                    // can’t be tapped is the very problem this state exists for.
                    if (uiState.isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // Toggled by the row, which owns the click and the semantics
                    Switch(
                        checked = isActive,
                        onCheckedChange = null,
                        colors = protectionSwitchColors()
                    )
                }
            }

            // Battery optimization prompt
            if (uiState.isEnabled && isBatteryOptimizationNeeded && !uiState.batteryPromptDismissed) {
                Spacer(modifier = Modifier.height(12.dp))
                BatteryOptimizationCard(
                    onExempt = {
                        // The one-tap `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog needs
                        // a Play-restricted permission; this opens the exemption list instead,
                        // where the user selects AWAGAM
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    onDismiss = { viewModel.dismissBatteryPrompt() }
                )
            }

            // Temporary disable section
            if (uiState.isEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                TemporaryDisableSection(
                    onDisable = { minutes ->
                        viewModel.temporaryDisable(minutes)
                        onToggleVpn(false)
                    }
                )
            }

            // Countdown display when temporarily disabled
            if (uiState.isTemporarilyDisabled && !uiState.isEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                CountdownCard(
                    seconds = uiState.disableCountdownSeconds,
                    onCancel = {
                        viewModel.cancelTemporaryDisable()
                        viewModel.startRequested()
                        onToggleVpn(true)
                    }
                )
            }

            // VPN error message (DoH errors show while enabled too)
            uiState.vpnError?.let { vpnError ->
                val isDoHError = vpnError.startsWith(UserPreferences.VPN_ERROR_DOH_FAILED)
                if (!uiState.isEnabled || isDoHError) {
                    Spacer(modifier = Modifier.height(12.dp))
                    VpnErrorCard(
                        error = vpnError,
                        onDismiss = { viewModel.clearVpnError() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Statistics cards
            StatCards(
                tldCount = uiState.tldCount,
                domainCount = uiState.domainCount,
                blockedCount = uiState.blockedCount
            )

            // Empty blocklist warning
            if (uiState.tldCount == 0 && uiState.domainCount == 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = WarningContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No Blocking Rules Configured",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val blocklistWarningText = buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append("Add blocklists containing TLDs or domains to start filtering. Use your own or ")
                            }
                            withLink(LinkAnnotation.Url(
                                url = "https://iadefensa.com/solutions/awagam-chromium/#blocklists"
                            )) {
                                withStyle(SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                )) {
                                    append("existing blocklists")
                                }
                            }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append(". URL-only blocklists are not supported at the DNS level.")
                            }
                        }
                        Text(
                            text = blocklistWarningText,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Manage blocklists button
            OutlinedButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage blocklists")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // VPN notice card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Using a VPN?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android allows only one VPN at a time. If you use a VPN for privacy or work, AWAGAM cannot run simultaneously.\n\n" +
                                "Alternative: Export your blocklists and import them into Pi-hole, AdGuard Home, or your VPN provider’s DNS filtering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL limitation notice
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "DNS Filtering Limitations",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // One `Text` with a blank line between the paragraphs, as the
                    // cards without links do: A spacer between two `Text`s would
                    // set the gap in dp against their 16.sp line
                    val annotatedText = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("DNS only sees domain names, not full URLs. Patterns like “example.com/path/*” cannot be blocked at the DNS level.\n\n")
                            append("For URL-level blocking, use ")
                        }
                        withLink(LinkAnnotation.Url(
                            url = "https://chromewebstore.google.com/detail/ia-defensa-awagam-tld-dom/efnpgpiffjglnijemnmdkemiliiialbm"
                        )) {
                            withStyle(SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline
                            )) {
                                append("the AWAGAM browser extension")
                            }
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" on desktop.")
                        }
                    }
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // How it works card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "How It Works",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AWAGAM creates a local VPN to intercept DNS queries. Blocked domains resolve to 0.0.0.0, preventing connections. Your actual Internet traffic is not routed through the VPN—only DNS lookups are filtered.\n\n" +
                                "Nothing is sent to IA Defensa, and there is no analytics or tracking. The only external connections are the ones filtering needs: Queries that blocklists don’t block go to the DNS provider you select, encrypted over DNS-over-HTTPS, and blocklists are fetched from the URLs you add.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }
    }
}

@Composable
private fun TemporaryDisableSection(
    onDisable: (minutes: Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Temporarily Disable",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onDisable(5) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("5 min")
                }
                FilledTonalButton(
                    onClick = { onDisable(15) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("15 min")
                }
                FilledTonalButton(
                    onClick = { onDisable(60) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1 hour")
                }
            }
        }
    }
}

@Composable
private fun CountdownCard(
    seconds: Int,
    onCancel: () -> Unit
) {
    val minutes = seconds / 60
    val secs = seconds % 60
    val timeString = "%d:%02d".format(minutes, secs)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // The card grey the rest of the screen uses
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Re-enabling in",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = timeString,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                // Brighter than the label: The number is what the card is for
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onCancel) {
                Text("Cancel and re-enable now")
            }
        }
    }
}

private data class Stat(
    val label: String,
    val value: String,
    val description: String
)

/** How the counts are sized, and whether that size fits three across. */
private data class StatSizing(
    val valueStyle: TextStyle,
    val fitsInRow: Boolean
)

@Composable
private fun StatCards(
    tldCount: Int,
    domainCount: Int,
    blockedCount: Long
) {
    val stats = listOf(
        Stat(
            label = "TLDs",
            value = formatCompact(tldCount.toLong()),
            description = "${NumberFormat.getNumberInstance().format(tldCount)} top-level domains blocked"
        ),
        Stat(
            label = "Domains",
            value = formatCompact(domainCount.toLong()),
            description = "${NumberFormat.getNumberInstance().format(domainCount)} domains blocked"
        ),
        Stat(
            label = "Blocked",
            value = formatCompact(blockedCount),
            description = "${NumberFormat.getNumberInstance().format(blockedCount)} requests blocked"
        )
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Blocking statistics" }
    ) {
        val available = (maxWidth - STAT_CARD_GAP * 2) / 3 - STAT_CARD_PADDING * 2
        val sizing = statSizing(stats.map { it.value }, available.coerceAtLeast(0.dp))

        if (sizing.fitsInRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(STAT_CARD_GAP)
            ) {
                stats.forEach { stat ->
                    StatCard(
                        modifier = Modifier.weight(1f),
                        stat = stat,
                        valueStyle = sizing.valueStyle
                    )
                }
            }
        } else {
            // A third of the row cannot hold the counts at any size the type
            // floor allows. Stacking gives each card the full width, which is
            // several times what even the widest count needs, so the numbers
            // go back to full size rather than shrink for room they now have.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(STAT_CARD_GAP)
            ) {
                stats.forEach { stat ->
                    StatCard(
                        modifier = Modifier.fillMaxWidth(),
                        stat = stat,
                        valueStyle = sizing.valueStyle
                    )
                }
            }
        }
    }
}

/**
 * Sizes the counts so that every one of [values] fits [available] on a single
 * line, and reports whether that succeeded. `formatCompact` already holds them
 * to four characters, which fits at the default font scale; this is what keeps
 * a raised scale or a narrow screen from breaking the row, since a number can
 * neither wrap—the card would grow taller than the two beside it—nor ellipsize,
 * which would misstate it.
 *
 * Stepping down from the full size rather than measuring per card is the point:
 * one size covers all three. The floor is in `sp`, so it still answers to the
 * font scale, and where even that overruns [available] the caller is told to
 * lay the cards out some other way instead of cutting a digit off.
 */
@Composable
private fun statSizing(values: List<String>, available: Dp): StatSizing {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fullSize = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)

    // Keyed on the measurer, which Compose rebuilds when the density or the
    // font scale changes
    return remember(values, available, fullSize, measurer) {
        val availableWidth = with(density) { available.toPx() }
        fun widestAt(style: TextStyle) = values.maxOf { value ->
            measurer.measure(value, style, softWrap = false).size.width
        }

        var style = fullSize
        var widest = widestAt(style)
        while (widest > availableWidth && style.fontSize > STAT_VALUE_MIN_FONT_SIZE) {
            style = style.copy(fontSize = (style.fontSize.value - 1).sp)
            widest = widestAt(style)
        }

        val fitsInRow = widest <= availableWidth
        StatSizing(
            valueStyle = if (fitsInRow) style else fullSize,
            fitsInRow = fitsInRow
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    stat: Stat,
    valueStyle: TextStyle
) {
    Card(
        modifier = modifier.semantics { contentDescription = stat.description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(STAT_CARD_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stat.value,
                style = valueStyle,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stat.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VpnErrorCard(
    error: String,
    onDismiss: () -> Unit
) {
    val isDoHError = error.startsWith(UserPreferences.VPN_ERROR_DOH_FAILED)
    val errorMessage = when {
        error == UserPreferences.VPN_ERROR_ANOTHER_VPN ->
            "Could not start protection. Another VPN appears to be active. Android only allows one VPN at a time."
        error == UserPreferences.VPN_ERROR_PERMISSION_DENIED ->
            "Android did not grant the VPN connection. If another VPN app is set as always-on, turn that off in your VPN settings, then try again."
        error == UserPreferences.VPN_ERROR_START_TIMEOUT ->
            "Protection did not start in time. Another VPN may be holding the connection, or the blocklists may be too large to load. Try again."
        isDoHError -> {
            val detail = error.removePrefix("${UserPreferences.VPN_ERROR_DOH_FAILED}:")
            "DNS upstream is not reachable ($detail). DNS queries will fail. Check your Internet connection, or pick a different DNS provider in the settings."
        }
        else ->
            "Could not start protection. Please try again."
    }
    val title = if (isDoHError) "DNS Upstream Unreachable" else "Protection Failed to Start"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCard(
    onExempt: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.battery_optimization_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.battery_optimization_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Dismiss")
                }
                Button(
                    onClick = onExempt,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.battery_optimization_action))
                }
            }
        }
    }
}