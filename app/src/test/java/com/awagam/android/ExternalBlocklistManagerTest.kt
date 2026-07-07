package com.awagam.android

import com.awagam.android.data.blocklist.BlocklistGroup
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for `ExternalBlocklistManager.convertToRawUrl` and `mergeImportedGroups`.
 * Verifies that hosting-platform blob URLs are correctly converted to direct
 * download URLs, and that bundle imports are merged with collision-safe group IDs.
 */
class ExternalBlocklistManagerTest {

    // GitHub

    @Test
    fun `GitHub blob URL is converted to raw`() {
        val input = "https://github.com/user/repo/blob/main/blocklist.json"
        val expected = "https://raw.githubusercontent.com/user/repo/main/blocklist.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `GitHub blob URL on non-main branch is converted to raw`() {
        val input = "https://github.com/user/repo/blob/develop/path/to/file.json"
        val expected = "https://raw.githubusercontent.com/user/repo/develop/path/to/file.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `already-raw GitHub URL is unchanged`() {
        val url = "https://raw.githubusercontent.com/user/repo/main/blocklist.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // GitLab

    @Test
    fun `GitLab blob URL is converted to raw`() {
        val input = "https://gitlab.com/user/repo/-/blob/main/blocklist.json"
        val expected = "https://gitlab.com/user/repo/-/raw/main/blocklist.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `GitLab blob URL on non-main branch is converted to raw`() {
        val input = "https://gitlab.com/group/subgroup/repo/-/blob/stable/path/file.json"
        val expected = "https://gitlab.com/group/subgroup/repo/-/raw/stable/path/file.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `already-raw GitLab URL is unchanged`() {
        val url = "https://gitlab.com/user/repo/-/raw/main/blocklist.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // Codeberg

    @Test
    fun `Codeberg blob URL is converted to raw`() {
        val input = "https://codeberg.org/user/repo/src/branch/main/blocklist.json"
        val expected = "https://codeberg.org/user/repo/raw/branch/main/blocklist.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `Codeberg blob URL on non-main branch is converted to raw`() {
        val input = "https://codeberg.org/user/repo/src/branch/feature/path/file.json"
        val expected = "https://codeberg.org/user/repo/raw/branch/feature/path/file.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `already-raw Codeberg URL is unchanged`() {
        val url = "https://codeberg.org/user/repo/raw/branch/main/blocklist.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // Pastebin

    @Test
    fun `Pastebin URL is converted to raw`() {
        val input = "https://pastebin.com/abcXYZ123"
        val expected = "https://pastebin.com/raw/abcXYZ123"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `already-raw Pastebin URL is unchanged`() {
        val url = "https://pastebin.com/raw/abcXYZ123"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // Non-Platform URLs

    @Test
    fun `arbitrary HTTPS URL is unchanged`() {
        val url = "https://example.com/blocklist.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    @Test
    fun `URL with no matching pattern is unchanged`() {
        val url = "https://cdn.example.org/lists/domains.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // Host-Exact Matching (Not Substring)

    @Test
    fun `URL whose path contains github-com substring is unchanged`() {
        val url = "https://example.com/mirror/github.com/blob/main/file.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    @Test
    fun `URL whose path contains gitlab-com substring is unchanged`() {
        val url = "https://example.com/mirror/gitlab.com/-/blob/main/file.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    // Bundle Merging

    private fun group(name: String) = BlocklistGroup(name = name, domains = listOf("example.com"))

    @Test
    fun `merged group IDs are prefixed per import`() {
        val merged = linkedMapOf<String, BlocklistGroup>()
        ExternalBlocklistManager.mergeImportedGroups(merged, mapOf("ads" to group("Ads"), "trackers" to group("Trackers")), 0)
        ExternalBlocklistManager.mergeImportedGroups(merged, mapOf("ads" to group("Other Ads")), 1)
        assertEquals(listOf("import1_ads", "import1_trackers", "import2_ads"), merged.keys.toList())
        assertEquals("Other Ads", merged["import2_ads"]?.name)
    }

    @Test
    fun `group ID collisions get a numeric suffix`() {
        // The numeric prefixes can’t collide across imports, so exercise the defensive branch directly
        val merged = linkedMapOf("import1_ads" to group("Existing"), "import1_ads_2" to group("Existing 2"))
        ExternalBlocklistManager.mergeImportedGroups(merged, mapOf("ads" to group("Ads")), 0)
        assertEquals(setOf("import1_ads", "import1_ads_2", "import1_ads_3"), merged.keys.toSet())
        assertEquals("Ads", merged["import1_ads_3"]?.name)
    }

    @Test
    fun `merging fails fast once the group limit is exceeded`() {
        val merged = linkedMapOf<String, BlocklistGroup>()
        ExternalBlocklistManager.mergeImportedGroups(merged, (1..60).associate { "g$it" to group("G$it") }, 0)
        try {
            ExternalBlocklistManager.mergeImportedGroups(merged, (1..60).associate { "g$it" to group("G$it") }, 1)
            fail("Expected the group limit to fail the merge")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Too many groups"))
        }
    }

    @Test
    fun `merging exactly 100 groups is allowed`() {
        val merged = linkedMapOf<String, BlocklistGroup>()
        ExternalBlocklistManager.mergeImportedGroups(merged, (1..50).associate { "g$it" to group("G$it") }, 0)
        ExternalBlocklistManager.mergeImportedGroups(merged, (1..50).associate { "g$it" to group("G$it") }, 1)
        assertEquals(100, merged.size)
    }

    // Bundle Resolution

    private fun bundleOf(vararg urls: String) = Json.parseToJsonElement(
        """{"imports": [${urls.joinToString(",") { "\"$it\"" }}]}"""
    )

    private val memberJson = """{"ads": {"name": "Ads", "domains": ["ads.example.com"]}}"""

    @Test
    fun `failing imports are skipped with a warning`() {
        val bundle = bundleOf("https://a.example/ok1.json", "https://a.example/dead.json", "https://a.example/ok2.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("dead")) throw Exception("HTTP 404") else memberJson
        }
        assertEquals(3, resolved.metadata.imports)
        assertEquals(2, resolved.metadata.importsLoaded)
        assertEquals(setOf("import1_ads", "import3_ads"), resolved.groups.keys.toSet())
        assertTrue(resolved.warning!!.startsWith("1 of 3 imported blocklists could not be loaded"))
        assertTrue(resolved.warning!!.contains("dead.json"))
    }

    @Test
    fun `resolution fails when no import can be loaded`() {
        val bundle = bundleOf("https://a.example/d1.json", "https://a.example/d2.json")
        try {
            ExternalBlocklistManager.resolveBundle(bundle, 100) { throw Exception("HTTP 404") }
            fail("Expected resolution to fail")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("None of the imported blocklists could be loaded"))
        }
    }

    @Test
    fun `nested bundles are skipped with a warning`() {
        val bundle = bundleOf("https://a.example/nested.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("nested")) """{"imports": ["https://x.example/y.json"]}""" else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("is itself a bundle"))
    }

    @Test
    fun `invalid member JSON is skipped with a warning`() {
        val bundle = bundleOf("https://a.example/bad.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("bad")) "{invalid" else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("bad.json"))
    }

    @Test
    fun `healthy bundles resolve without warning`() {
        val bundle = bundleOf("https://a.example/ok1.json", "https://a.example/ok2.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { memberJson }
        assertEquals(null, resolved.warning)
        assertEquals(2, resolved.metadata.importsLoaded)
        assertEquals(2, resolved.metadata.totalRules)
    }

    @Test
    fun `resolution fails when the combined size limit is exceeded`() {
        val bundle = bundleOf("https://a.example/ok.json")
        try {
            ExternalBlocklistManager.resolveBundle(bundle, 10 * 1024 * 1024 - 10) { memberJson }
            fail("Expected resolution to fail")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Bundle too large"))
        }
    }

    @Test
    fun `bundle size is counted in UTF-8 bytes`() {
        // “Ä” is one UTF-16 unit but two UTF-8 bytes—a character-based count would pass this bundle
        val member = """{"ads": {"name": "Äds", "domains": ["ads.example.com"]}}"""
        val bundle = bundleOf("https://a.example/ok.json")
        try {
            ExternalBlocklistManager.resolveBundle(bundle, 10 * 1024 * 1024 - member.length) { member }
            fail("Expected resolution to fail")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Bundle too large"))
        }
    }
}
