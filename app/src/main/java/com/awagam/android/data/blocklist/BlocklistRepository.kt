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
import java.net.IDN

/**
 * Repository for managing blocklists.
 * Handles loading, parsing, and querying blocked domains/TLDs.
 */
class BlocklistRepository(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistRepository"
        private const val BUILTIN_BLOCKLIST = "blocklist.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val externalBlocklistManager = ExternalBlocklistManager(context)

    // Normalized sets for fast lookups
    private val blockedTlds = mutableSetOf<String>()
    private val blockedDomains = mutableSetOf<String>()
    private val blockedUrls = mutableSetOf<String>() // For export to Pi-hole/AdGuard
    private var externalSourceCount = 0

    private val _blocklistStats = MutableStateFlow(BlocklistStats())
    val blocklistStats: StateFlow<BlocklistStats> = _blocklistStats.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    /**
     * Load blocklists from built-in assets and any configured external sources.
     */
    suspend fun loadBlocklists() = withContext(Dispatchers.IO) {
        blockedTlds.clear()
        blockedDomains.clear()
        blockedUrls.clear()

        // Load built-in blocklist
        loadBuiltinBlocklist()

        // Load external blocklists
        loadExternalBlocklists()

        updateStats()
        Log.d(TAG, "Loaded ${blockedTlds.size} TLDs, ${blockedDomains.size} domains, ${blockedUrls.size} URLs")
    }

    private suspend fun loadExternalBlocklists() {
        externalSourceCount = 0
        try {
            val configs = externalBlocklistManager.blocklistsFlow.first()
            configs.filter { it.enabled }.forEach { config ->
                val cached = externalBlocklistManager.getCachedBlocklist(config.id)
                if (cached != null) {
                    parseBlocklist(cached)
                    externalSourceCount++
                    Log.d(TAG, "Loaded blocklist: ${config.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists", e)
        }
    }

    private fun loadBuiltinBlocklist() {
        try {
            val jsonString = context.assets.open(BUILTIN_BLOCKLIST).bufferedReader().use {
                it.readText()
            }
            parseBlocklist(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load built-in blocklist", e)
        }
    }

    private fun parseBlocklist(jsonString: String) {
        try {
            val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

            groups.values.forEach { group ->
                // Normalize TLDs (ensure they start with a dot)
                group.tlds.forEach { tld ->
                    val normalized = if (tld.startsWith(".")) tld else ".$tld"
                    blockedTlds.add(normalized.lowercase())
                }

                // Normalize domains (convert IDN to punycode, strip www prefix)
                group.domains.forEach { domain ->
                    val normalized = normalizeDomain(domain).removePrefix("www.")
                    blockedDomains.add(normalized)
                }

                // Store URLs for export to Pi-hole/AdGuard
                // (URLs can’t be blocked at DNS level, but other tools can use them)
                group.urls.forEach { url ->
                    blockedUrls.add(url.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse blocklist JSON", e)
        }
    }

    private fun normalizeDomain(domain: String): String {
        return try {
            IDN.toASCII(domain.lowercase().trim())
        } catch (e: Exception) {
            domain.lowercase().trim()
        }
    }

    private fun updateStats() {
        _blocklistStats.value = BlocklistStats(
            tldCount = blockedTlds.size,
            domainCount = blockedDomains.size,
            urlCount = blockedUrls.size,
            sourceCount = 1 + externalSourceCount
        )
    }

    /**
     * Check if a domain should be blocked.
     * Checks against both TLD and domain blocklists.
     */
    fun isBlocked(hostname: String): Boolean {
        val normalized = normalizeDomain(hostname)

        // Check exact domain match
        if (blockedDomains.contains(normalized)) {
            incrementBlockedCount()
            return true
        }

        // Check subdomain matches (e.g., sub.blocked.com matches blocked.com)
        blockedDomains.forEach { blockedDomain ->
            if (normalized.endsWith(".$blockedDomain")) {
                incrementBlockedCount()
                return true
            }
        }

        // Check TLD matches
        blockedTlds.forEach { tld ->
            if (normalized.endsWith(tld)) {
                incrementBlockedCount()
                return true
            }
        }

        return false
    }

    private fun incrementBlockedCount() {
        _blockedCount.value++
    }

    /**
     * Reset the blocked count (e.g., at start of day or on demand).
     */
    fun resetBlockedCount() {
        _blockedCount.value = 0
    }

    /**
     * Get all blocked TLDs for export.
     */
    fun getBlockedTlds(): Set<String> = blockedTlds.toSet()

    /**
     * Get all blocked domains for export.
     */
    fun getBlockedDomains(): Set<String> = blockedDomains.toSet()

    /**
     * Get all blocked URLs for export (for Pi-hole/AdGuard).
     */
    fun getBlockedUrls(): Set<String> = blockedUrls.toSet()
}