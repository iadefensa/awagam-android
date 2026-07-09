package com.awagam.android.data.blocklist

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

        // Bundle imports are fetched concurrently in batches within an overall
        // deadline, so a bundle with many slow or dead imports can't stall the
        // periodic worker past its execution window
        private const val BUNDLE_FETCH_CONCURRENCY = 5
        private val BUNDLE_FETCH_TIME_BUDGET_MS = TimeUnit.MINUTES.toMillis(5)

        // Ceiling for a single import fetch attempt (covering the whole
        // `fetchWithFallbacks` chain, not just one HTTP call)—`fetchWithFallbacks`
        // doesn’t share one timeout across its GitHub/jsDelivr/API methods the
        // way AWAGAM Chromium’s `fetchFromGitHubWithFallbacks` does, so without
        // this a single import could take minutes and stall a whole batch well
        // past the deadline check between batches
        private val BUNDLE_IMPORT_FETCH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)

        // Matches AWAGAM Chromium’s `fetchBlocklistData` retry count and
        // exponential backoff (2^attempt seconds)
        private const val BUNDLE_IMPORT_MAX_ATTEMPTS = 3

        // Shared across every blocklist in one `refreshAllBlocklists()` pass—
        // WorkManager gives a periodic worker roughly 10 minutes before the
        // system stops it, so several bad bundles must share one budget
        // instead of each getting a fresh BUNDLE_FETCH_TIME_BUDGET_MS
        private val TOTAL_REFRESH_TIME_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        internal fun convertToRawUrl(url: String): String {
            val uri = try { java.net.URI(url) } catch (e: Exception) { return url }
            val hostLower = uri.host?.lowercase() ?: return url

            if (hostLower == "github.com") {
                // The URL kind is the third path segment—“/owner/repo/blob/…” is a
                // file page, “/owner/repo/tree/…” a directory; matching “/tree/”
                // anywhere in the URL would falsely reject file paths that merely
                // contain such a segment
                val kind = uri.path?.split("/")?.getOrNull(3)
                if (kind == "blob") {
                    return url
                        .replaceFirst("://github.com/", "://raw.githubusercontent.com/", ignoreCase = true)
                        .replaceFirst("/blob/", "/")
                }
                // Directories aren’t fetchable files—rejecting them here gives a
                // clearer message than the fetch failure they’d produce later
                // (matches AWAGAM Chromium’s `convertToRawUrl`)
                if (kind == "tree") {
                    throw Exception("Directory URLs are not supported. Please link to a specific file.")
                }
            }

            if (hostLower == "gitlab.com" && url.contains("/-/blob/")) {
                return url.replace("/-/blob/", "/-/raw/")
            }

            if (hostLower == "codeberg.org" && url.contains("/src/branch/")) {
                return url.replace("/src/branch/", "/raw/branch/")
            }

            if (hostLower == "pastebin.com" && !url.contains("/raw/")) {
                val regex = Regex("pastebin\\.com/([a-zA-Z0-9]+)$", RegexOption.IGNORE_CASE)
                val match = regex.find(url)
                if (match != null) {
                    return url.replace(
                        "pastebin.com/${match.groupValues[1]}",
                        "pastebin.com/raw/${match.groupValues[1]}",
                        ignoreCase = true
                    )
                }
            }

            return url
        }

        /**
         * Merge one imported blocklist’s groups into the accumulated bundle result.
         * Group IDs are prefixed per import to avoid conflicts. Fails fast once the
         * group limit is exceeded, since further imports can’t make the merged
         * result validate.
         */
        internal fun mergeImportedGroups(
            merged: MutableMap<String, BlocklistGroup>,
            importGroups: Map<String, BlocklistGroup>,
            index: Int
        ) {
            importGroups.forEach { (groupId, group) ->
                var mergedId = "import${index + 1}_$groupId"
                var suffix = 2
                while (merged.containsKey(mergedId)) {
                    mergedId = "import${index + 1}_${groupId}_$suffix"
                    suffix++
                }
                merged[mergedId] = group
            }

            if (merged.size > BlocklistValidator.MAX_GROUPS) {
                throw Exception("Validation failed for the combined blocklists of this bundle: Too many groups (max ${BlocklistValidator.MAX_GROUPS})")
            }
        }

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Resolved bundle—merged groups of all imported blocklists, plus metadata
         * and an optional warning naming skipped imports.
         */
        internal data class ResolvedBundle(
            val groups: Map<String, BlocklistGroup>,
            val metadata: BlocklistMetadata,
            val warning: String? = null
        )

        private class BundleImportEntry(val importUrl: String, val fetchUrl: String? = null) {
            var body: String? = null
            var failure: String? = null
        }

        /**
         * Resolve a bundle by fetching and merging all imported blocklists.
         * Imports that are invalid, duplicates, or fail to load or validate
         * are skipped with a warning; the refresh fails only if no import can
         * be loaded, or if the merged result exceeds the blocklist limits.
         * Fetching is injected so the resolution logic can be tested without
         * a network.
         */
        internal suspend fun resolveBundle(
            bundleElement: JsonElement,
            bundleSize: Int,
            concurrency: Int = BUNDLE_FETCH_CONCURRENCY,
            deadline: Long = System.currentTimeMillis() + BUNDLE_FETCH_TIME_BUDGET_MS,
            importFetchTimeoutMs: Long = BUNDLE_IMPORT_FETCH_TIMEOUT_MS,
            retryBackoffUnit: Long = TimeUnit.SECONDS.toMillis(1),
            fetchImport: (String) -> String
        ): ResolvedBundle {
            // Fail clearly instead of letting `chunked()` throw mid-resolution
            require(concurrency > 0) { "concurrency must be positive" }

            // Only structural problems are fatal—per-URL problems are skipped below
            val structureValidation = BlocklistValidator.validateBundleStructure(bundleElement)
            if (!structureValidation.valid) {
                throw Exception("Validation failed: ${structureValidation.error}")
            }

            // Validate URLs and drop duplicates before fetching anything
            val seenUrls = mutableSetOf<String>()
            val entries = structureValidation.imports.map { importUrl ->
                val urlValidation = BlocklistValidator.validateImportUrl(importUrl) { convertToRawUrl(it) }
                when {
                    !urlValidation.valid ->
                        BundleImportEntry(importUrl).apply { failure = "$importUrl (${urlValidation.error})" }
                    !seenUrls.add(urlValidation.normalizedUrl) ->
                        BundleImportEntry(importUrl).apply { failure = "$importUrl (duplicate import)" }
                    else -> BundleImportEntry(importUrl, fetchUrl = urlValidation.normalizedUrl)
                }
            }

            // Fetch in small concurrent batches—fetching one import at a time could
            // stall a large bundle for a very long time, since per-fetch timeouts add
            // up; the overall deadline bounds the worst case regardless, by skipping
            // any batch that hasn’t started once the budget runs out
            val toFetch = entries.filter { it.fetchUrl != null }
            coroutineScope {
                for (batch in toFetch.chunked(concurrency)) {
                    if (System.currentTimeMillis() >= deadline) {
                        // Distinct from the per-attempt “timed out” below—this import was
                        // never even started, not slow to respond
                        batch.forEach { it.failure = "${it.importUrl} (time budget exceeded)" }
                        continue
                    }
                    batch.map { entry ->
                        val fetchUrl = entry.fetchUrl!!
                        async(Dispatchers.IO) {
                            // Retries for transient failures; each attempt is capped at
                            // `BUNDLE_IMPORT_FETCH_TIMEOUT_MS` so a single slow import can’t
                            // hold up the whole batch. `fetchImport` is a plain blocking call
                            // with no suspension points, so `withTimeoutOrNull` alone can’t cut
                            // it off—cancellation is cooperative and only checked at suspension
                            // points. `runInterruptible` bridges that: on timeout it interrupts
                            // the underlying thread, which `OkHttp` (and `Thread.sleep`) honor
                            var body: String? = null
                            var error: String? = null
                            for (attempt in 1..BUNDLE_IMPORT_MAX_ATTEMPTS) {
                                try {
                                    body = withTimeoutOrNull(importFetchTimeoutMs) {
                                        runInterruptible { fetchImport(fetchUrl) }
                                    }
                                    error = if (body == null) "timed out" else null
                                } catch (e: Exception) {
                                    error = e.message ?: e.javaClass.simpleName
                                }
                                if (body != null) break
                                if (attempt < BUNDLE_IMPORT_MAX_ATTEMPTS) {
                                    // Exponential backoff before retrying—be a respectful
                                    // client to third-party servers, not a rapid-fire one
                                    delay((1L shl attempt) * retryBackoffUnit)
                                }
                            }
                            entry.body = body
                            if (body == null) entry.failure = "${entry.importUrl} ($error)"
                        }
                    }.awaitAll()
                }
            }

            // Validate and merge in import order, so the group ID prefixes stay deterministic
            val merged = linkedMapOf<String, BlocklistGroup>()
            val failures = mutableListOf<String>()
            var totalSize = bundleSize
            var importsLoaded = 0

            entries.forEachIndexed { index, entry ->
                if (entry.failure != null) {
                    failures.add(entry.failure!!)
                    return@forEachIndexed
                }
                val importBody = entry.body!!

                // Reject oversized bodies before parsing them
                val importSize = BlocklistValidator.utf8Size(importBody)
                if (importSize > MAX_BLOCKLIST_SIZE) {
                    failures.add("${entry.importUrl} (blocklist too large (max 10 MB))")
                    return@forEachIndexed
                }

                // Validate the member; failures skip it, they don’t fail the bundle
                val importGroups: Map<String, BlocklistGroup>? = try {
                    val importElement = json.parseToJsonElement(importBody)
                    if (!BlocklistValidator.validateJsonDepth(importElement)) {
                        throw Exception("JSON structure too deeply nested")
                    }
                    if (BlocklistValidator.isBundle(importElement)) {
                        throw Exception("is itself a bundle—bundles may only import plain blocklists")
                    }
                    val groups: Map<String, BlocklistGroup> = json.decodeFromString(importBody)
                    val validation = BlocklistValidator.validateBlocklistFormat(groups)
                    if (!validation.valid) {
                        throw Exception(validation.error)
                    }
                    groups
                } catch (e: Exception) {
                    failures.add("${entry.importUrl} (${e.message ?: e.javaClass.simpleName})")
                    null
                }

                if (importGroups != null) {
                    // Only imports that contribute rules count toward the combined size
                    // limit—a skipped import must not be able to fail the bundle
                    totalSize += importSize
                    if (totalSize > MAX_BLOCKLIST_SIZE) {
                        throw Exception("Bundle too large. The combined size of all imported blocklists exceeds 10 MB.")
                    }
                    // Group-limit failures inside the merge stay fatal
                    mergeImportedGroups(merged, importGroups, index)
                    importsLoaded++
                }
            }

            if (importsLoaded == 0) {
                throw Exception("None of the imported blocklists could be loaded: ${failures.joinToString("; ")}")
            }

            // The merged result must satisfy the same limits as a single blocklist
            val mergedValidation = BlocklistValidator.validateBlocklistFormat(merged)
            if (!mergedValidation.valid) {
                throw Exception("Validation failed for the combined blocklists of this bundle: ${mergedValidation.error}")
            }

            val metadata = (mergedValidation.metadata ?: BlocklistMetadata()).copy(
                imports = structureValidation.imports.size,
                importsLoaded = importsLoaded
            )
            val warning = if (failures.isNotEmpty()) {
                "${failures.size} of ${structureValidation.imports.size} imports skipped: ${failures.joinToString("; ")}"
            } else {
                null
            }
            return ResolvedBundle(merged, metadata, warning)
        }
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
    suspend fun refreshBlocklist(
        id: String,
        deadline: Long = System.currentTimeMillis() + BUNDLE_FETCH_TIME_BUDGET_MS
    ) = withContext(Dispatchers.IO) {
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

            val bodyToCache: String
            val metadata: BlocklistMetadata
            var warning: String? = null

            if (BlocklistValidator.isBundle(jsonElement)) {
                // Bundles reference other blocklists instead of containing rules
                // `resolveBundle` passes already-normalized import URLs
                val resolved = resolveBundle(jsonElement, BlocklistValidator.utf8Size(body), deadline = deadline) { importUrl ->
                    fetchWithFallbacks(importUrl, importUrl)
                }
                bodyToCache = json.encodeToString(resolved.groups)
                // Guard the cache as well—group ID prefixes can grow the merged result past the fetched sizes
                if (!BlocklistValidator.validateSize(bodyToCache)) {
                    throw Exception("Bundle too large. The merged blocklist exceeds 10 MB.")
                }
                metadata = resolved.metadata
                warning = resolved.warning
            } else {
                // Parse into blocklist groups
                val groups: Map<String, BlocklistGroup> = json.decodeFromString(body)

                // Validate blocklist format (TLDs, domains, URLs)
                val validationResult = BlocklistValidator.validateBlocklistFormat(groups)
                if (!validationResult.valid) {
                    throw Exception("Validation failed: ${validationResult.error}")
                }
                bodyToCache = body
                metadata = validationResult.metadata ?: BlocklistMetadata()
            }

            // Cache the blocklist (bundles are cached as their merged blocklist)
            context.blocklistDataStore.edit { preferences ->
                preferences[stringPreferencesKey(BLOCKLIST_CACHE_PREFIX + id)] = bodyToCache
            }

            // Update with success status and validated metadata; bundles with
            // skipped imports stay active but carry a warning naming them
            updateBlocklist(config.copy(
                lastUpdated = nowIso,
                lastAttempted = nowIso,
                status = if (warning != null) "warning" else "active",
                errorMessage = warning?.let {
                    BlocklistValidator.sanitizeConfig(config.copy(errorMessage = it)).errorMessage
                },
                metadata = metadata
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
                // Reject oversized responses before reading the body into memory
                // (“-1” means the length is unknown—then the post-download checks apply)
                val contentLength = response.body?.contentLength() ?: -1L
                if (contentLength > MAX_BLOCKLIST_SIZE) {
                    throw Exception("Blocklist too large. Maximum size is 10 MB.")
                }
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
        refreshBlocklists { true }
    }

    /**
     * Refresh the enabled blocklists matching (`shouldRefresh`), least recently
     * attempted first, within one shared time budget—so several bad bundles
     * can’t each claim a fresh `BUNDLE_FETCH_TIME_BUDGET_MS` and collectively
     * run the periodic worker past its execution window, and a chronically
     * slow or failing blocklist can’t claim the budget every pass and starve
     * the configs after it.
     */
    private suspend fun refreshBlocklists(shouldRefresh: (ExternalBlocklistConfig) -> Boolean) {
        val deadline = System.currentTimeMillis() + TOTAL_REFRESH_TIME_BUDGET_MS
        val configs = getConfigsSnapshot()
            .filter { it.enabled && shouldRefresh(it) }
            // ISO timestamps sort lexicographically; never-attempted configs go first
            .sortedBy { it.lastAttempted ?: "" }
        for (config in configs) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "Stopping blocklist refresh early: time budget exhausted")
                break
            }
            refreshBlocklist(config.id, deadline)
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
        refreshBlocklists { needsUpdate(it) }
    }
}
