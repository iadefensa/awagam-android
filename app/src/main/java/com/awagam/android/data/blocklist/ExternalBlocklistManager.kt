package com.awagam.android.data.blocklist

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private val Context.blocklistDataStore: DataStore<Preferences> by preferencesDataStore(name = "blocklists")

/**
 * Manages external blocklist configurations and fetching.
 */
class ExternalBlocklistManager(private val context: Context) {

    companion object {
        private const val TAG = "ExternalBlocklistManager"
        private val BLOCKLIST_CONFIGS = stringPreferencesKey("blocklist_configs")
        private val BLOCKLIST_CACHE_PREFIX = "blocklist_cache_"
        private const val MAX_BLOCKLIST_SIZE = 10 * 1024 * 1024 // 10 MB

        internal fun convertToRawUrl(url: String): String {
            if (url.contains("github.com") && url.contains("/blob/")) {
                return url
                    .replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            }

            if (url.contains("gitlab.com") && url.contains("/-/blob/")) {
                return url.replace("/-/blob/", "/-/raw/")
            }

            if (url.contains("codeberg.org") && url.contains("/src/branch/")) {
                return url.replace("/src/branch/", "/raw/branch/")
            }

            if (url.contains("pastebin.com") && !url.contains("/raw/")) {
                val regex = Regex("pastebin\\.com/([a-zA-Z0-9]+)$")
                val match = regex.find(url)
                if (match != null) {
                    return url.replace("pastebin.com/${match.groupValues[1]}", "pastebin.com/raw/${match.groupValues[1]}")
                }
            }

            return url
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Flow of all configured external blocklists.
     */
    val blocklistsFlow: Flow<List<ExternalBlocklistConfig>> = context.blocklistDataStore.data
        .map { preferences ->
            val jsonString = preferences[BLOCKLIST_CONFIGS] ?: "[]"
            try {
                json.decodeFromString(jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse blocklist configs", e)
                emptyList()
            }
        }

    /**
     * Add a new external blocklist configuration.
     * Validates URL and sanitizes input before saving.
     */
    suspend fun addBlocklist(config: ExternalBlocklistConfig) {
        // Validate URL security
        if (!BlocklistValidator.isValidBlocklistUrl(config.url)) {
            throw IllegalArgumentException("Invalid or insecure URL. Only HTTPS URLs from public hosts are allowed.")
        }

        // Sanitize config
        val sanitized = BlocklistValidator.sanitizeConfig(config)

        context.blocklistDataStore.edit { preferences ->
            val current = getCurrentConfigs(preferences)
            val updated = current + sanitized
            preferences[BLOCKLIST_CONFIGS] = json.encodeToString(updated)
        }
    }

    /**
     * Update an existing blocklist configuration.
     */
    suspend fun updateBlocklist(config: ExternalBlocklistConfig) {
        context.blocklistDataStore.edit { preferences ->
            val current = getCurrentConfigs(preferences)
            val updated = current.map { if (it.id == config.id) config else it }
            preferences[BLOCKLIST_CONFIGS] = json.encodeToString(updated)
        }
    }

    /**
     * Toggle blocklist enabled state.
     */
    suspend fun toggleBlocklist(id: String) {
        context.blocklistDataStore.edit { preferences ->
            val current = getCurrentConfigs(preferences)
            val updated = current.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
            preferences[BLOCKLIST_CONFIGS] = json.encodeToString(updated)
        }
    }

    /**
     * Delete a blocklist configuration.
     */
    suspend fun deleteBlocklist(id: String) {
        context.blocklistDataStore.edit { preferences ->
            val current = getCurrentConfigs(preferences)
            val updated = current.filter { it.id != id }
            preferences[BLOCKLIST_CONFIGS] = json.encodeToString(updated)
            // Also remove cached data
            preferences.remove(stringPreferencesKey(BLOCKLIST_CACHE_PREFIX + id))
        }
    }

    /**
     * Import blocklists from a list of configurations.
     * Validates URLs and sanitizes all configs before saving.
     */
    suspend fun importBlocklists(configs: List<ExternalBlocklistConfig>) {
        // Validate and sanitize all configs
        val validConfigs = configs.mapNotNull { config ->
            try {
                if (!BlocklistValidator.isValidBlocklistUrl(config.url)) {
                    Log.w(TAG, "Skipping invalid URL during import: ${config.url}")
                    return@mapNotNull null
                }
                BlocklistValidator.sanitizeConfig(config)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid config during import: ${config.name}", e)
                null
            }
        }

        context.blocklistDataStore.edit { preferences ->
            val current = getCurrentConfigs(preferences)
            // Merge by URL, preferring new configs
            val currentByUrl = current.associateBy { it.url }
            val newByUrl = validConfigs.associateBy { it.url }
            val merged = (currentByUrl + newByUrl).values.toList()
            preferences[BLOCKLIST_CONFIGS] = json.encodeToString(merged)
        }
    }

    /**
     * Fetch and cache a blocklist from its URL.
     * Includes security validations: size limits, JSON depth, format validation.
     */
    suspend fun refreshBlocklist(id: String) = withContext(Dispatchers.IO) {
        val configs = getConfigsSnapshot()
        val config = configs.find { it.id == id } ?: return@withContext
        val nowIso = getIsoTimestamp()

        // Mark as attempting
        updateBlocklist(config.copy(lastAttempted = nowIso))

        try {
            // Convert URL to raw format if needed (GitHub, GitLab, Pastebin, etc.)
            val fetchUrl = convertToRawUrl(config.url)
            if (fetchUrl != config.url) {
                Log.d(TAG, "Converted URL: ${config.url} → $fetchUrl")
            }

            val body = fetchWithFallbacks(fetchUrl, config.url)

            // Validate size (DoS protection)
            if (!BlocklistValidator.validateSize(body)) {
                throw Exception("Blocklist too large. Maximum size is 10 MB.")
            }

            // Parse JSON
            val jsonElement = json.parseToJsonElement(body)

            // Validate JSON depth (DoS protection)
            if (!BlocklistValidator.validateJsonDepth(jsonElement)) {
                throw Exception("JSON structure too deeply nested. Maximum depth is 20.")
            }

            // Parse into blocklist groups
            val groups: Map<String, BlocklistGroup> = json.decodeFromString(body)

            // Validate blocklist format (TLDs, domains, URLs)
            val validationResult = BlocklistValidator.validateBlocklistFormat(groups)
            if (!validationResult.valid) {
                throw Exception("Validation failed: ${validationResult.error}")
            }

            // Cache the blocklist
            context.blocklistDataStore.edit { preferences ->
                preferences[stringPreferencesKey(BLOCKLIST_CACHE_PREFIX + id)] = body
            }

            // Update with success status and validated metadata
            updateBlocklist(config.copy(
                lastUpdated = nowIso,
                lastAttempted = nowIso,
                status = "active",
                errorMessage = null,
                metadata = validationResult.metadata
            ))

            Log.d(TAG, "Refreshed blocklist: ${config.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh blocklist: ${config.name}", e)
            updateBlocklist(config.copy(
                lastAttempted = nowIso,
                status = "error",
                errorMessage = BlocklistValidator.sanitizeConfig(
                    config.copy(errorMessage = e.message)
                ).errorMessage
            ))
        }
    }

    /**
     * Fetch URL with fallbacks for GitHub URLs.
     * Tries: 1) Raw URL, 2) jsDelivr CDN, 3) GitHub API (base64 decode)
     */
    private fun fetchWithFallbacks(primaryUrl: String, originalUrl: String): String {
        val errors = mutableListOf<String>()

        // Method 1: Try primary URL first
        try {
            val result = fetchUrl(primaryUrl)
            if (result != null) return result
            errors.add("Primary URL returned null")
        } catch (e: Exception) {
            errors.add("Primary: ${e.message}")
        }

        // For GitHub URLs, try additional fallbacks
        if (primaryUrl.contains("raw.githubusercontent.com")) {
            // Method 2: Try jsDelivr CDN fallback
            try {
                val jsdelivrUrl = primaryUrl
                    .replace("raw.githubusercontent.com", "cdn.jsdelivr.net/gh")
                    .replaceFirst(Regex("/([^/]+)/([^/]+)/([^/]+)/(.+)"), "/$1/$2@$3/$4")
                Log.d(TAG, "Trying jsDelivr fallback: $jsdelivrUrl")
                val result = fetchUrl(jsdelivrUrl)
                if (result != null) return result
                errors.add("jsDelivr returned null")
            } catch (e: Exception) {
                errors.add("jsDelivr: ${e.message}")
            }

            // Method 3: Try GitHub API (for public repos)
            try {
                // Convert raw.githubusercontent.com/user/repo/branch/path
                // to api.github.com/repos/user/repo/contents/path
                val apiUrl = primaryUrl
                    .replace("raw.githubusercontent.com", "api.github.com/repos")
                    .replaceFirst(Regex("/([^/]+)/([^/]+)/([^/]+)/(.+)"), "/$1/$2/contents/$4")
                Log.d(TAG, "Trying GitHub API fallback: $apiUrl")
                val result = fetchGitHubApi(apiUrl)
                if (result != null) return result
                errors.add("GitHub API returned null")
            } catch (e: Exception) {
                errors.add("GitHub API: ${e.message}")
            }
        }

        throw Exception("All fetch methods failed: ${errors.joinToString(", ")}")
    }

    /**
     * Fetch content from GitHub API and decode base64.
     */
    private fun fetchGitHubApi(apiUrl: String): String? {
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "AWAGAM-Android/1.0")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null

                // Parse GitHub API response
                val apiResponse = json.parseToJsonElement(body).jsonObject
                val content = apiResponse["content"]?.jsonPrimitive?.content
                val encoding = apiResponse["encoding"]?.jsonPrimitive?.content

                if (content != null && encoding == "base64") {
                    Log.d(TAG, "GitHub API fallback successful, decoding base64")
                    return BlocklistValidator.decodeBase64(content)
                }
                return null
            } else {
                throw Exception("HTTP ${response.code}")
            }
        }
    }

    /**
     * Fetch a URL and return its body, or null if not successful.
     */
    private fun fetchUrl(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AWAGAM-Android/1.0")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                // Check if we got HTML instead of JSON (common error)
                if (body != null && body.trimStart().startsWith("<!DOCTYPE") || body?.trimStart()?.startsWith("<html") == true) {
                    throw Exception("Received HTML instead of JSON. Use raw/direct URL.")
                }
                return body
            } else {
                throw Exception("HTTP ${response.code}")
            }
        }
    }

    private fun calculateMetadata(groups: Map<String, BlocklistGroup>): BlocklistMetadata {
        var tlds = 0
        var domains = 0
        var urls = 0

        groups.values.forEach { group ->
            tlds += group.tlds.size
            domains += group.domains.size
            urls += group.urls.size
        }

        return BlocklistMetadata(
            totalRules = tlds + domains + urls,
            tlds = tlds,
            domains = domains,
            urls = urls,
            groups = groups.size
        )
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * Refresh all enabled blocklists.
     */
    suspend fun refreshAllBlocklists() {
        val configs = getConfigsSnapshot()
        configs.filter { it.enabled }.forEach { config ->
            refreshBlocklist(config.id)
        }
    }

    /**
     * Get cached blocklist data for a specific config.
     */
    suspend fun getCachedBlocklist(id: String): String? {
        val preferences = context.blocklistDataStore.data.first()
        return preferences[stringPreferencesKey(BLOCKLIST_CACHE_PREFIX + id)]
    }

    /**
     * Get all cached blocklist data for enabled configs.
     */
    suspend fun getAllCachedBlocklists(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val preferences = context.blocklistDataStore.data.first()
        val configs = getCurrentConfigs(preferences)
        configs.filter { it.enabled }.forEach { config ->
            val cached = preferences[stringPreferencesKey(BLOCKLIST_CACHE_PREFIX + config.id)]
            if (cached != null) {
                result[config.id] = cached
            }
        }
        result
    }

    private fun getCurrentConfigs(preferences: Preferences): List<ExternalBlocklistConfig> {
        val jsonString = preferences[BLOCKLIST_CONFIGS] ?: "[]"
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getConfigsSnapshot(): List<ExternalBlocklistConfig> {
        val preferences = context.blocklistDataStore.data.first()
        return getCurrentConfigs(preferences)
    }

    /**
     * Check if a blocklist needs to be updated based on its update interval.
     */
    fun needsUpdate(config: ExternalBlocklistConfig): Boolean {
        val lastUpdated = config.lastUpdated ?: return true

        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val lastUpdateTime = sdf.parse(lastUpdated)?.time ?: return true
            val now = System.currentTimeMillis()
            (now - lastUpdateTime) >= config.updateInterval
        } catch (e: Exception) {
            true // If we can't parse the date, assume update is needed
        }
    }

    /**
     * Refresh blocklists that need updating based on their update interval.
     */
    suspend fun refreshBlocklistsIfNeeded() {
        val configs = getConfigsSnapshot()
        configs.filter { it.enabled && needsUpdate(it) }.forEach { config ->
            Log.d(TAG, "Blocklist ${config.name} needs update, refreshing...")
            refreshBlocklist(config.id)
        }
    }
}
