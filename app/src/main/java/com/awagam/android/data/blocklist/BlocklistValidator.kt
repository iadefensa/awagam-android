// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.data.blocklist

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.net.URL

/**
 * Validates blocklist content and URLs for security.
 * Matches browser extension validation logic for consistency.
 */
object BlocklistValidator {

    // Limits matching browser extension
    private const val MAX_BLOCKLIST_SIZE = 10 * 1024 * 1024 // 10 MB
    private const val MAX_JSON_DEPTH = 20
    const val MAX_GROUPS = 100
    // Matches the 100-group blocklist limit, since more (non-empty) imports
    // could never validate after merging anyway
    private const val MAX_BUNDLE_IMPORTS = 100
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_URL_LENGTH = 2000
    private const val MAX_ERROR_LENGTH = 500
    private const val MAX_DNS_LABEL_LENGTH = 63
    private const val MAX_DOMAIN_LENGTH = 253

    // Number of skipped entries named in a warning—a broken blocklist may have
    // hundreds of thousands, so only these are kept while all are counted
    internal const val MAX_LISTED_SKIP_DETAILS = 10

    /**
     * Validation result with optional error message and metadata. Results of
     * `[validateBlocklist]` also carry the kept groups and what was skipped.
     */
    data class ValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val metadata: BlocklistMetadata? = null,
        val groups: Map<String, BlocklistGroup>? = null,
        /** At most [MAX_LISTED_SKIP_DETAILS]; the counts below include all */
        val skipped: List<String> = emptyList(),
        val skippedEntries: Int = 0,
        val skippedGroups: Int = 0
    )

    /**
     * Validate blocklist URL for security.
     * Only allows HTTPS, blocks private/internal networks.
     *
     * This is a check on the URL alone. A public hostname can still resolve to
     * an internal address, so the address a request actually connects to is
     * checked again at fetch time via `[isBlockedAddress]`.
     */
    fun isValidBlocklistUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)

            // Only allow HTTPS
            if (parsed.protocol != "https") {
                return false
            }

            val hostname = parsed.host.lowercase()
            if (hostname.isEmpty()) {
                return false
            }

            // Block localhost
            if (hostname == "localhost" || hostname.endsWith(".localhost")) {
                return false
            }

            // Block IP literals pointing anywhere but the public internet
            val literal = parseIpLiteral(hostname)
            if (literal != null) {
                return !isBlockedAddress(literal)
            }

            // Block internal domains
            val internalDomains = listOf(".local", ".internal", ".corp", ".home")
            if (internalDomains.any { hostname.endsWith(it) }) {
                return false
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse a hostname that is an IP address literal, or return null if it is a
     * name. Only literals are parsed—passing a name to `InetAddress` here would
     * resolve it over the network.
     */
    fun parseIpLiteral(hostname: String): InetAddress? {
        // `URL.getHost()` keeps the brackets around an IPv6 literal
        val candidate = if (hostname.startsWith("[") && hostname.endsWith("]")) {
            hostname.substring(1, hostname.length - 1)
        } else {
            hostname
        }

        val isIpv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(candidate)
        val isIpv6 = candidate.contains(":") &&
            Regex("""^[0-9a-fA-F:.]+$""").matches(candidate)
        if (!isIpv4 && !isIpv6) return null

        return try {
            // Resolves nothing for a literal, so no lookup is performed
            InetAddress.getByName(candidate)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether an address is outside the public internet, and so must not be
     * fetched from: loopback, private, link-local (including the cloud metadata
     * endpoint), shared/CGNAT, multicast, and reserved ranges.
     */
    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val bytes = address.address
        // `InetAddress` maps IPv4-in-IPv6 forms to `Inet4Address`, so a
        // four-byte address here covers both notations
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val third = bytes[2].toInt() and 0xFF
            return when {
                first == 0 -> true                          // 0.0.0.0/8 “this network”
                first == 100 && second in 64..127 -> true   // 100.64.0.0/10 CGNAT
                first == 192 && second == 0 && third == 0 -> true // 192.0.0.0/24 IETF protocol assignments
                first == 198 && second in 18..19 -> true    // 198.18.0.0/15 benchmarking
                first >= 240 -> true                        // 240.0.0.0/4 reserved, incl. broadcast
                else -> false
            }
        }

        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xFF
            // fc00::/7 unique local addresses—`isSiteLocalAddress` only covers
            // the deprecated fec0::/10
            return (first and 0xFE) == 0xFC
        }

        return false
    }

    /**
     * Validate blocklist size.
     */
    fun validateSize(content: String): Boolean {
        return utf8Size(content) <= MAX_BLOCKLIST_SIZE
    }

    /**
     * UTF-8 byte size of a string—the size limit is a byte limit, not a character count.
     */
    fun utf8Size(content: String): Int {
        return content.toByteArray(Charsets.UTF_8).size
    }

    /**
     * Calculate JSON depth to prevent DoS attacks.
     */
    fun getJsonDepth(element: JsonElement, currentDepth: Int = 1): Int {
        return when (element) {
            is JsonObject -> {
                var maxChildDepth = currentDepth
                for ((_, value) in element) {
                    val childDepth = getJsonDepth(value, currentDepth + 1)
                    if (childDepth > maxChildDepth) {
                        maxChildDepth = childDepth
                    }
                }
                maxChildDepth
            }
            is JsonArray -> {
                var maxChildDepth = currentDepth
                for (item in element) {
                    val childDepth = getJsonDepth(item, currentDepth + 1)
                    if (childDepth > maxChildDepth) {
                        maxChildDepth = childDepth
                    }
                }
                maxChildDepth
            }
            else -> currentDepth
        }
    }

    /**
     * Validate JSON depth for DoS protection.
     */
    fun validateJsonDepth(element: JsonElement): Boolean {
        return getJsonDepth(element) <= MAX_JSON_DEPTH
    }

    /**
     * Bundle validation result with the list of import URLs on success.
     */
    data class BundleValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val imports: List<String> = emptyList()
    )

    /**
     * Detect the AWAGAM bundle format (an object whose “imports” field is an array).
     * Matches browser extension detection logic.
     */
    fun isBundle(element: JsonElement): Boolean {
        return element is JsonObject && element["imports"] is JsonArray
    }

    /**
     * Validate the bundle envelope—only problems that make the file unusable
     * as a whole are reported here; per-URL problems are handled by the
     * callers (fatal via `validateBundleFormat`, skippable at runtime).
     * Returns the import URLs on success.
     */
    fun validateBundleStructure(element: JsonElement): BundleValidationResult {
        if (!isBundle(element)) {
            return BundleValidationResult(false, "A bundle must be an object with an \"imports\" array")
        }

        val obj = element as JsonObject
        val extraKeys = obj.keys.filter { it != "imports" }
        if (extraKeys.isNotEmpty()) {
            return BundleValidationResult(false, "A bundle must contain only the \"imports\" field (found: ${extraKeys.joinToString(", ")})")
        }

        val importsArray = obj["imports"] as JsonArray
        if (importsArray.isEmpty()) {
            return BundleValidationResult(false, "\"imports\" must contain at least one URL")
        }
        if (importsArray.size > MAX_BUNDLE_IMPORTS) {
            return BundleValidationResult(false, "Too many imports (max $MAX_BUNDLE_IMPORTS)")
        }

        val urls = mutableListOf<String>()
        for (item in importsArray) {
            val url = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return BundleValidationResult(false, "\"imports\" contains a non-string entry")
            urls.add(url)
        }

        return BundleValidationResult(true, imports = urls)
    }

    /**
     * Import URL validation result with the normalized URL on success.
     */
    data class ImportUrlValidationResult(
        val valid: Boolean,
        val error: String? = null,
        val normalizedUrl: String = ""
    )

    /**
     * Validate a single import URL, returning its normalized form for
     * fetching and duplicate detection. The original URL is validated too—a
     * normalizer must not be able to turn an insecure URL into an acceptable one.
     */
    fun validateImportUrl(
        url: String,
        normalizeUrl: (String) -> String = { it }
    ): ImportUrlValidationResult {
        val normalizedUrl = try {
            normalizeUrl(url)
        } catch (e: Exception) {
            return ImportUrlValidationResult(false, e.message ?: "invalid import URL")
        }
        if (url.length > MAX_URL_LENGTH || normalizedUrl.length > MAX_URL_LENGTH) {
            return ImportUrlValidationResult(false, "import URL too long (max $MAX_URL_LENGTH characters)")
        }
        if (!isValidBlocklistUrl(url) || !isValidBlocklistUrl(normalizedUrl)) {
            return ImportUrlValidationResult(false, "invalid or insecure import URL")
        }
        return ImportUrlValidationResult(true, normalizedUrl = normalizedUrl)
    }

    /**
     * Validate AWAGAM bundle format (strict—any invalid or duplicate import
     * fails; at runtime, bundle resolution skips such imports instead).
     * A bundle contains only the “imports” field with 1–100 unique HTTPS URLs.
     * Pass a normalizer so the same blocklist can’t be imported twice via
     * different URL representations (e.g., GitHub blob vs. raw).
     */
    fun validateBundleFormat(
        element: JsonElement,
        normalizeUrl: (String) -> String = { it }
    ): BundleValidationResult {
        val structureValidation = validateBundleStructure(element)
        if (!structureValidation.valid) {
            return structureValidation
        }

        val seenUrls = mutableSetOf<String>()
        for (url in structureValidation.imports) {
            val urlValidation = validateImportUrl(url, normalizeUrl)
            if (!urlValidation.valid) {
                return BundleValidationResult(false, "$url (${urlValidation.error})")
            }
            if (!seenUrls.add(urlValidation.normalizedUrl)) {
                return BundleValidationResult(false, "Duplicate import URL: $url")
            }
        }

        return BundleValidationResult(true, imports = structureValidation.imports)
    }

    /**
     * Validate a parsed AWAGAM blocklist, skipping invalid entries and groups
     * without a name instead of failing (matching AWAGAM Chromium’s tolerant
     * mode). Returns the kept groups, with metadata counting only those.
     * Structural errors and the group limit still fail validation.
     */
    fun validateBlocklist(element: JsonElement): ValidationResult {
        if (element !is JsonObject) {
            return ValidationResult(false, "Root must be an object")
        }

        val groups = linkedMapOf<String, BlocklistGroup>()
        val skipped = mutableListOf<String>()
        var skippedEntries = 0
        var skippedGroups = 0

        for ((groupId, groupElement) in element) {
            if (groupElement !is JsonObject) {
                return ValidationResult(false, "Group \"$groupId\" must be an object")
            }

            val name = (groupElement["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name.isNullOrBlank()) {
                if (skipped.size < MAX_LISTED_SKIP_DETAILS) {
                    skipped.add("Group \"$groupId\" missing required \"name\" field")
                }
                skippedGroups++
                continue
            }

            val fields = mutableMapOf<String, List<String>>()
            for (field in listOf("tlds", "domains", "urls")) {
                val fieldElement = groupElement[field] ?: continue
                if (fieldElement !is JsonArray) {
                    return ValidationResult(false, "Group \"$groupId\".$field must be an array")
                }
                fields[field] = fieldElement.mapNotNull { item ->
                    val value = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val valid = value != null && entryError(field, value, groupId) == null
                    if (!valid) {
                        // Messages are only built for entries that will be listed
                        if (skipped.size < MAX_LISTED_SKIP_DETAILS) {
                            skipped.add(
                                if (value == null) "Group \"$groupId\".$field contains non-string item"
                                else entryError(field, value, groupId)!!
                            )
                        }
                        skippedEntries++
                    }
                    value.takeIf { valid }
                }
            }

            groups[groupId] = BlocklistGroup(
                name = name,
                context = groupElement["context"],
                tlds = fields["tlds"].orEmpty(),
                domains = fields["domains"].orEmpty(),
                urls = fields["urls"].orEmpty()
            )
        }

        if (groups.size > MAX_GROUPS) {
            return ValidationResult(false, "Too many groups (max $MAX_GROUPS)")
        }

        return ValidationResult(
            valid = true,
            metadata = countRules(groups),
            groups = groups,
            skipped = skipped,
            skippedEntries = skippedEntries,
            skippedGroups = skippedGroups
        )
    }

    /**
     * Validate AWAGAM blocklist format strictly, failing on the first invalid
     * entry—for blocklists already cleaned by `[validateBlocklist]`, like the
     * merged result of a bundle.
     * Returns validation result with metadata on success.
     */
    fun validateBlocklistFormat(groups: Map<String, BlocklistGroup>): ValidationResult {
        if (groups.size > MAX_GROUPS) {
            return ValidationResult(false, "Too many groups (max $MAX_GROUPS)")
        }

        for ((groupId, group) in groups) {
            if (group.name.isBlank()) {
                return ValidationResult(false, "Group \"$groupId\" missing required \"name\" field")
            }
            for ((field, entries) in listOf("tlds" to group.tlds, "domains" to group.domains, "urls" to group.urls)) {
                for (entry in entries) {
                    entryError(field, entry, groupId)?.let { return ValidationResult(false, it) }
                }
            }
        }

        return ValidationResult(valid = true, metadata = countRules(groups))
    }

    /**
     * The error for an invalid TLD, domain, or URL entry, or null if it’s valid.
     */
    private fun entryError(field: String, entry: String, groupId: String): String? = when {
        field == "tlds" && !isValidTld(entry) -> "Invalid TLD in group \"$groupId\": $entry"
        field == "domains" && !isValidDomain(entry) -> "Invalid domain in group \"$groupId\": $entry"
        field == "urls" && !isValidBlocklistEntry(entry) -> "Invalid URL in group \"$groupId\": $entry"
        else -> null
    }

    private fun countRules(groups: Map<String, BlocklistGroup>): BlocklistMetadata {
        val tlds = groups.values.sumOf { it.tlds.size }
        val domains = groups.values.sumOf { it.domains.size }
        val urls = groups.values.sumOf { it.urls.size }
        return BlocklistMetadata(
            totalRules = tlds + domains + urls,
            tlds = tlds,
            domains = domains,
            urls = urls,
            groups = groups.size
        )
    }

    /**
     * Validate TLD format.
     * TLDs must start with a dot and contain valid DNS labels.
     */
    fun isValidTld(tld: String): Boolean {
        if (!tld.startsWith(".")) return false

        val cleanTld = tld.substring(1)
        if (cleanTld.isEmpty() || cleanTld.length > MAX_DOMAIN_LENGTH) return false

        // Split into labels (for multi-level TLDs like .ac.uk, .com.au)
        val labels = cleanTld.split(".")
        return labels.all { isValidDnsLabel(it) }
    }

    /**
     * Validate individual DNS label according to RFC 1035/1123.
     */
    fun isValidDnsLabel(label: String): Boolean {
        // Label must not be empty and must be 63 characters or less
        if (label.isEmpty() || label.length > MAX_DNS_LABEL_LENGTH) return false

        // Must not start or end with hyphen
        if (label.startsWith("-") || label.endsWith("-")) return false

        // Check for valid characters (ASCII letters, digits, hyphens, Unicode for IDN)
        val validChars = Regex("^[a-zA-Z0-9\\u00a1-\\uffff-]+$")
        if (!validChars.matches(label)) return false

        // Additional check for punycode labels
        if (label.startsWith("xn--")) {
            val punycodeData = label.substring(4)
            if (punycodeData.isEmpty() || punycodeData.length > 59) return false
            val asciiOnly = Regex("^[a-zA-Z0-9-]+$")
            if (!asciiOnly.matches(punycodeData)) return false
        }

        return true
    }

    /**
     * Validate domain format.
     * Supports both ASCII and internationalized domain names (IDN).
     */
    fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        if (domain.length > MAX_DOMAIN_LENGTH) return false
        if (domain.contains("..")) return false

        // Check for partial IP patterns (e.g., "142.91.159." for blocking IP ranges)
        val partialIpPattern = Regex("""^(\d{1,3}\.){1,3}\d{0,3}\.?$""")
        if (partialIpPattern.matches(domain)) {
            val octets = domain.trimEnd('.').split(".")
            return octets.all { octet ->
                val num = octet.toIntOrNull()
                num != null && num in 0..255
            }
        }

        return try {
            // Try to convert to punycode - this validates IDN
            val ascii = DomainMatcher.toAscii(domain.lowercase().trim()) ?: return false

            // Validate the ASCII result
            val labels = ascii.split(".")
            labels.all { isValidDnsLabel(it) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate URL entry in blocklist.
     * More permissive than blocklist URL validation - allows paths and wildcards.
     */
    fun isValidBlocklistEntry(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains(Regex("\\s"))) return false // No whitespace

        return try {
            // Try parsing as full URL first
            if (url.startsWith("http://") || url.startsWith("https://")) {
                URL(url)
                true
            } else {
                // Protocol-less URL (e.g., "example.com/path")
                val firstSlash = url.indexOf('/')
                val hostname = if (firstSlash == -1) url else url.substring(0, firstSlash)

                // Hostname validation (allow wildcards)
                if (hostname.isEmpty() || hostname.length > MAX_DOMAIN_LENGTH) return false

                // Allow wildcards in hostname
                val hostnamePattern = Regex("^[a-zA-Z0-9\\u00a1-\\uffff.*-]+$")
                hostnamePattern.matches(hostname)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sanitize blocklist configuration to prevent security issues.
     */
    fun sanitizeConfig(config: ExternalBlocklistConfig): ExternalBlocklistConfig {
        return config.copy(
            name = sanitizeString(config.name, MAX_NAME_LENGTH),
            url = config.url.take(MAX_URL_LENGTH),
            errorMessage = config.errorMessage?.let { sanitizeString(it, MAX_ERROR_LENGTH) }
        )
    }

    /**
     * Sanitize a string by removing angle brackets (so it can’t carry HTML tags)
     * and limiting length, with an ellipsis when truncated.
     */
    private fun sanitizeString(input: String, maxLength: Int): String {
        val stripped = input.replace(Regex("[<>]"), "")
        return if (stripped.length > maxLength) stripped.take(maxLength - 1) + "…" else stripped
    }

    /**
     * Decode base64 content (for GitHub API responses).
     */
    fun decodeBase64(content: String): String {
        val cleanContent = content.replace(Regex("\\s"), "") // Remove whitespace
        val decoded = Base64.decode(cleanContent, Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8)
    }
}