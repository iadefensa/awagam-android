package com.awagam.android.data.blocklist

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports blocklists to formats compatible with other DNS filtering tools.
 * Useful for users who use a VPN and cannot run AWAGAM simultaneously.
 */
class BlocklistExporter(private val context: Context) {

    enum class Format(val extension: String, val mimeType: String) {
        PIHOLE("txt", "text/plain"),
        ADGUARD("txt", "text/plain"),
        HOSTS("txt", "text/plain")
    }

    /**
     * Export blocklist to specified format and return a share intent.
     */
    suspend fun export(
        domains: Set<String>,
        tlds: Set<String>,
        urls: Set<String>,
        format: Format
    ): Intent = withContext(Dispatchers.IO) {
        val content = when (format) {
            Format.PIHOLE -> generatePihole(domains, tlds, urls)
            Format.ADGUARD -> generateAdGuard(domains, tlds, urls)
            Format.HOSTS -> generateHosts(domains, tlds, urls)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val filename = "awagam-blocklist-$timestamp.${format.extension}"

        val file = File(context.cacheDir, filename)
        file.writeText(content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AWAGAM Blocklist (${format.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Get export content as string (for clipboard).
     */
    fun getExportString(
        domains: Set<String>,
        tlds: Set<String>,
        urls: Set<String>,
        format: Format
    ): String {
        return when (format) {
            Format.PIHOLE -> generatePihole(domains, tlds, urls)
            Format.ADGUARD -> generateAdGuard(domains, tlds, urls)
            Format.HOSTS -> generateHosts(domains, tlds, urls)
        }
    }

    /**
     * Pi-hole format: one domain per line.
     * TLDs are converted to regex patterns.
     * URLs are converted to regex patterns for path matching.
     */
    private fun generatePihole(domains: Set<String>, tlds: Set<String>, urls: Set<String>): String {
        val lines = mutableListOf<String>()
        lines.add("# AWAGAM Blocklist for Pi-hole")
        lines.add("# Generated: ${Date()}")
        lines.add("# Domains: ${domains.size}, TLDs: ${tlds.size}, URLs: ${urls.size}")
        lines.add("")

        if (tlds.isNotEmpty()) {
            lines.add("# TLDs (use regex or wildcard patterns in Pi-hole)")
            tlds.sorted().forEach { tld ->
                // Pi-hole regex format for TLD blocking
                val cleanTld = tld.removePrefix(".")
                lines.add("(^|\\.)$cleanTld\$")
            }
            lines.add("")
        }

        if (domains.isNotEmpty()) {
            lines.add("# Domains")
            domains.sorted().forEach { domain ->
                lines.add(domain)
            }
            lines.add("")
        }

        if (urls.isNotEmpty()) {
            lines.add("# URLs (Pi-hole can use regex patterns for path matching)")
            urls.sorted().forEach { url ->
                // Extract domain from URL for basic blocking
                val domain = extractDomainFromUrl(url)
                if (domain != null) {
                    // Create regex pattern that matches the URL path
                    val pattern = urlToRegex(url)
                    lines.add("# URL: $url")
                    lines.add(pattern)
                }
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * AdGuard Home format: ||domain^ syntax.
     * URLs are converted to AdGuard filter syntax.
     */
    private fun generateAdGuard(domains: Set<String>, tlds: Set<String>, urls: Set<String>): String {
        val lines = mutableListOf<String>()
        lines.add("! AWAGAM Blocklist for AdGuard Home")
        lines.add("! Generated: ${Date()}")
        lines.add("! Domains: ${domains.size}, TLDs: ${tlds.size}, URLs: ${urls.size}")
        lines.add("")

        if (tlds.isNotEmpty()) {
            lines.add("! TLDs")
            tlds.sorted().forEach { tld ->
                val cleanTld = tld.removePrefix(".")
                // AdGuard wildcard for entire TLD
                lines.add("||*.$cleanTld^")
            }
            lines.add("")
        }

        if (domains.isNotEmpty()) {
            lines.add("! Domains")
            domains.sorted().forEach { domain ->
                lines.add("||$domain^")
            }
            lines.add("")
        }

        if (urls.isNotEmpty()) {
            lines.add("! URLs")
            urls.sorted().forEach { url ->
                // AdGuard supports URL patterns directly
                val pattern = urlToAdGuardPattern(url)
                lines.add(pattern)
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Standard hosts file format: 0.0.0.0 domain
     * URLs are skipped entirely (hosts files only support domain-level blocking).
     */
    private fun generateHosts(domains: Set<String>, tlds: Set<String>, urls: Set<String>): String {
        val lines = mutableListOf<String>()
        lines.add("# AWAGAM Blocklist (hosts format)")
        lines.add("# Generated: ${Date()}")
        lines.add("# Domains: ${domains.size}")
        lines.add("# Note: TLDs and URLs cannot be blocked via hosts file")
        lines.add("")

        if (tlds.isNotEmpty()) {
            lines.add("# TLDs (${tlds.size})—not included, hosts format doesn’t support wildcards")
            tlds.sorted().forEach { tld ->
                lines.add("# Skipped TLD: $tld")
            }
            lines.add("")
        }

        if (domains.isNotEmpty()) {
            lines.add("# Domains")
            domains.sorted().forEach { domain ->
                lines.add("0.0.0.0 $domain")
                lines.add("0.0.0.0 www.$domain")
            }
            lines.add("")
        }

        if (urls.isNotEmpty()) {
            lines.add("# URLs (${urls.size})—not included, hosts format only supports domain-level blocking")
            urls.sorted().forEach { url ->
                lines.add("# Skipped URL: $url")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Extract domain from a URL string.
     */
    private fun extractDomainFromUrl(url: String): String? {
        return try {
            val cleaned = url
                .removePrefix("https://")
                .removePrefix("http://")
            val slashIndex = cleaned.indexOf('/')
            val queryIndex = cleaned.indexOf('?')
            val endIndex = when {
                slashIndex >= 0 && queryIndex >= 0 -> minOf(slashIndex, queryIndex)
                slashIndex >= 0 -> slashIndex
                queryIndex >= 0 -> queryIndex
                else -> cleaned.length
            }
            cleaned.substring(0, endIndex).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert URL to Pi-hole regex pattern.
     */
    private fun urlToRegex(url: String): String {
        val cleaned = url
            .removePrefix("https://")
            .removePrefix("http://")
        // Escape special regex characters and convert wildcards
        val escaped = cleaned
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", "\\?")
        return "^(https?://)?$escaped"
    }

    /**
     * Convert URL to AdGuard filter pattern.
     */
    private fun urlToAdGuardPattern(url: String): String {
        val cleaned = url
            .removePrefix("https://")
            .removePrefix("http://")
        // AdGuard uses `||` for domain start and `^` for separator
        return if (cleaned.contains("/") || cleaned.contains("?")) {
            "||$cleaned"
        } else {
            "||$cleaned^"
        }
    }
}