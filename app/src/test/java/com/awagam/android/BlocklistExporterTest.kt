// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BlocklistExporter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for BlocklistExporter output formats.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlocklistExporterTest {

    private val exporter = BlocklistExporter(ApplicationProvider.getApplicationContext<Application>())

    private fun piholeRules(domains: Set<String>, tlds: Set<String>): List<String> =
        exporter.getExportString(domains, tlds, emptySet(), BlocklistExporter.Format.PIHOLE)
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `Pi-hole export uses ABP-style rules, which Pi-hole lists accept and apply to subdomains`() {
        assertEquals(
            listOf("||co.uk^", "||ru^", "||example.com^"),
            piholeRules(setOf("example.com"), setOf(".ru", ".co.uk"))
        )
    }

    private fun export(format: BlocklistExporter.Format, urls: Set<String>): List<String> =
        exporter.getExportString(emptySet(), emptySet(), urls, format).lines()

    private val pathUrls = setOf("https://example.com/ads/*", "tracker.example/pixel?id=1")

    @Test
    fun `Pi-hole export lists URL entries as comments instead of rules`() {
        val lines = export(BlocklistExporter.Format.PIHOLE, pathUrls)
        val rules = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(rules.isEmpty())
        pathUrls.forEach { url -> assertTrue(lines.contains("# Skipped URL: $url")) }
    }

    @Test
    fun `AdGuard Home export lists URL entries as comments instead of rules`() {
        val lines = export(BlocklistExporter.Format.ADGUARD, pathUrls)
        val rules = lines.filter { it.isNotBlank() && !it.startsWith("!") }
        assertTrue(rules.isEmpty())
        pathUrls.forEach { url -> assertTrue(lines.contains("! Skipped URL: $url")) }
    }
}