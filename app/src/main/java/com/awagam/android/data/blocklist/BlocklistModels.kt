// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.TimeUnit

/**
 * How often blocklists are refreshed—one cadence for every list, rather than the
 * per-list value an imported config carries, which nothing in the UI would show.
 * Also the value exported to the browser extension, which does honor per-list
 * intervals; keep it within the range that accepts (1 hour to 1 week).
 */
val BLOCKLIST_REFRESH_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(24)

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
    // An imported value is kept for export fidelity but not obeyed here; this
    // app refreshes every list on `BLOCKLIST_REFRESH_INTERVAL_MS`
    val updateInterval: Long = BLOCKLIST_REFRESH_INTERVAL_MS,
    val status: String = "pending",
    val errorMessage: String? = null,
    val metadata: BlocklistMetadata? = null
)

/**
 * What deleting this blocklist takes with it beyond the entry itself, or null
 * when the entry is all there is to lose.
 * A bundle stands for the lists it imports, and their URLs appear nowhere in
 * the UI—the count is the only warning that more than one source is going.
 */
fun ExternalBlocklistConfig.deletionImpact(): String? {
    val imports = metadata?.importsLoaded ?: 0
    if (imports <= 0) return null
    // Spelled out rather than the “blocklist(s)” the snackbars use: Those read
    // as a count alone, this one has to agree with a verb
    return if (imports == 1) {
        "This is a bundle, and 1 imported blocklist goes with it. " +
            "Its URL is not listed here, so adding it back means going through the bundle again."
    } else {
        "This is a bundle, and $imports imported blocklists go with it. " +
            "Their URLs are not listed here, so adding them back means going through the bundle again."
    }
}

/**
 * Export format matching browser extension.
 */
@Serializable
data class AwagamConfigExport(
    val version: String = "2.1.0",
    val exportedAt: String,
    val externalBlocklists: Map<String, ExternalBlocklistConfig>
)