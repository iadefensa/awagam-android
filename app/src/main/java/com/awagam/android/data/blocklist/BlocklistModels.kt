// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a group of blocked entries in the AWAGAM JSON format.
 * Compatible with the browser extension blocklist format.
 * Note: context is JsonElement to accept string, array, or null from various blocklist sources.
 */
@Serializable
data class BlocklistGroup(
    val name: String,
    val context: JsonElement? = null,
    val tlds: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val urls: List<String> = emptyList()
)

/**
 * Statistics about loaded blocklists.
 */
data class BlocklistStats(
    val tldCount: Int = 0,
    val domainCount: Int = 0,
    val urlCount: Int = 0,
    val sourceCount: Int = 0
)

/**
 * Metadata about a blocklist’s contents.
 * Matches browser extension format.
 */
@Serializable
data class BlocklistMetadata(
    val totalRules: Int = 0,
    val tlds: Int = 0,
    val domains: Int = 0,
    val urls: Int = 0,
    val groups: Int = 0,
    val imports: Int = 0,
    val importsLoaded: Int = 0
)

/**
 * Configuration for an external blocklist.
 * Matches browser extension format for import/export compatibility.
 */
@Serializable
data class ExternalBlocklistConfig(
    val id: String,
    val name: String,
    val url: String,
    val format: String = "awagam-json",
    val enabled: Boolean = true,
    val lastUpdated: String? = null,
    val lastAttempted: String? = null,
    // Kept for import/export compatibility with the browser extension; refreshes
    // are scheduled by `ExternalBlocklistManager.REFRESH_INTERVAL_MS` instead
    val updateInterval: Long = 86400000, // 24 hours in milliseconds
    val status: String = "pending",
    val errorMessage: String? = null,
    val metadata: BlocklistMetadata? = null
)

/**
 * Export format matching browser extension.
 */
@Serializable
data class AwagamConfigExport(
    val version: String = "2.1.0",
    val exportedAt: String,
    val externalBlocklists: Map<String, ExternalBlocklistConfig>
)