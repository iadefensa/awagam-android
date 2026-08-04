// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.awagam.android.ui.viewmodel.StatisticsViewModel
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val viewModel: StatisticsViewModel = viewModel()
    val statistics by viewModel.statisticsFlow.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        if (statistics == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Session Statistics
                StatsCard(title = "Current Session") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatRow(
                            label = "Uptime",
                            value = viewModel.formatUptime(statistics?.sessionUptime ?: 0L)
                        )
                        StatRow(
                            label = "Queries",
                            value = NumberFormat.getNumberInstance().format(statistics?.sessionQueries ?: 0)
                        )
                        StatRow(
                            label = "Blocked",
                            value = NumberFormat.getNumberInstance().format(statistics?.sessionBlocked ?: 0)
                        )
                        ProgressRow(
                            label = "Block Rate",
                            progress = (statistics?.blockRate?.toFloat() ?: 0f),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Performance Statistics
                StatsCard(title = "Performance") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatRow(
                            label = "Cache Hits",
                            value = NumberFormat.getNumberInstance().format(statistics?.cacheHits ?: 0)
                        )
                        StatRow(
                            label = "Cache Misses",
                            value = NumberFormat.getNumberInstance().format(statistics?.cacheMisses ?: 0)
                        )
                        ProgressRow(
                            label = "Cache Hit Rate",
                            progress = (statistics?.cacheHitRate?.toFloat() ?: 0f),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Overall Statistics
                StatsCard(title = "All Time") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatRow(
                            label = "Total Queries",
                            value = NumberFormat.getNumberInstance().format(statistics?.totalQueries ?: 0)
                        )
                        StatRow(
                            label = "Total Blocked",
                            value = NumberFormat.getNumberInstance().format(statistics?.blockedQueries ?: 0)
                        )
                        StatRow(
                            label = "Data Processed",
                            value = viewModel.formatDataUsage(statistics?.totalBytes ?: 0L)
                        )
                    }
                }

                // Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetSession() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset session")
                    }

                    OutlinedButton(
                        onClick = { viewModel.refreshStatistics() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    content: @Composable () -> Unit
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ProgressRow(
    label: String,
    progress: Float,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        // Simple progress bar without animation APIs
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "%.1f".format(progress * 100).removeSuffix(".0") + "%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}