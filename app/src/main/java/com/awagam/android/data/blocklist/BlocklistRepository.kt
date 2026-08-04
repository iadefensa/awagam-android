// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Repository for managing blocklists.
 * Handles loading, parsing, and querying blocked domains/TLDs.
 */
class BlocklistRepository(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistRepository"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val externalBlocklistManager = ExternalBlocklistManager(context)

    // Swapped atomically after a load completes so in-flight DNS queries always
    // see a complete rule set, never a cleared or half-populated one
    @Volatile
    private var matcher: DomainMatcher = DomainMatcher.EMPTY

    @Volatile
    private var blockedUrls: Set<String> = emptySet() // For export to Pi-hole/AdGuard

    private val _blocklistStats = MutableStateFlow(BlocklistStats())
    val blocklistStats: StateFlow<BlocklistStats> = _blocklistStats.asStateFlow()

    /**
     * Load blocklists from the user’s configured sources.
     * The app ships with no rules of its own—all blocking is user-configured.
     */
    suspend fun loadBlocklists() = withContext(Dispatchers.IO) {
        // Build into fresh collections, then publish; the live matcher stays
        // intact and keeps blocking for the duration of the load
        val builder = DomainMatcher.Companion.Builder()
        val urls = mutableSetOf<String>()

        val sourceCount = loadExternalBlocklists(builder, urls)

        val loaded = builder.build()
        matcher = loaded
        blockedUrls = urls.toSet()

        _blocklistStats.value = BlocklistStats(
            tldCount = loaded.tldCount,
            domainCount = loaded.domainCount,
            urlCount = blockedUrls.size,
            sourceCount = sourceCount
        )
        Log.d(TAG, "Loaded ${loaded.tldCount} TLDs, ${loaded.domainCount} domains, ${blockedUrls.size} URLs")
    }

    /** Returns the number of external sources that loaded successfully. */
    private suspend fun loadExternalBlocklists(
        builder: DomainMatcher.Companion.Builder,
        urls: MutableSet<String>
    ): Int {
        var count = 0
        try {
            val configs = externalBlocklistManager.blocklistsFlow.first()
            configs.filter { it.enabled }.forEach { config ->
                val cached = externalBlocklistManager.getCachedBlocklist(config.id)
                if (cached != null && parseBlocklist(cached, builder, urls)) {
                    count++
                    Log.d(TAG, "Loaded blocklist: ${config.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists", e)
        }
        return count
    }

    /**
     * Returns true if the source parsed. An empty but valid list still counts as a
     * source—a user’s list may legitimately be empty.
     */
    private fun parseBlocklist(
        jsonString: String,
        builder: DomainMatcher.Companion.Builder,
        urls: MutableSet<String>
    ): Boolean {
        return try {
            val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

            groups.values.forEach { group ->
                group.tlds.forEach { builder.addTld(it) }
                group.domains.forEach { builder.addDomain(it) }

                // Store URLs for export to Pi-hole/AdGuard
                // (URLs can’t be blocked at DNS level, but other tools can use them)
                group.urls.forEach { urls.add(it.trim()) }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse blocklist JSON", e)
            false
        }
    }

    /**
     * Check if a domain should be blocked.
     * Checks against both TLD and domain blocklists.
     */
    fun isBlocked(hostname: String): Boolean = matcher.isBlocked(hostname)

    /**
     * Get all blocked TLDs for export.
     */
    fun getBlockedTlds(): Set<String> = matcher.tlds

    /**
     * Get all blocked domains for export.
     */
    fun getBlockedDomains(): Set<String> = matcher.domains

    /**
     * Get all blocked URLs for export (for Pi-hole/AdGuard).
     */
    fun getBlockedUrls(): Set<String> = blockedUrls.toSet()
}