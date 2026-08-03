// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import com.awagam.android.data.blocklist.BlocklistGroup
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `GitHub tree URL is rejected as a directory`() {
        try {
            ExternalBlocklistManager.convertToRawUrl("https://github.com/user/repo/tree/main/lists")
            fail("Expected directory URL to be rejected")
        } catch (err: Exception) {
            assertTrue(err.message!!.contains("Directory URLs are not supported"))
        }
    }

    @Test
    fun `GitHub file URL with a tree segment in its file path is converted, not rejected`() {
        val input = "https://github.com/user/repo/blob/main/tree/list.json"
        val expected = "https://raw.githubusercontent.com/user/repo/main/tree/list.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
    }

    @Test
    fun `GitHub URL with tree outside the kind segment is unchanged`() {
        // A release asset whose tag is named “tree” is neither a file page nor a directory
        val url = "https://github.com/user/repo/releases/download/tree/list.json"
        assertEquals(url, ExternalBlocklistManager.convertToRawUrl(url))
    }

    @Test
    fun `GitHub file URL with a second blob segment in its file path keeps that segment`() {
        val input = "https://github.com/user/repo/blob/main/blob/list.json"
        val expected = "https://raw.githubusercontent.com/user/repo/main/blob/list.json"
        assertEquals(expected, ExternalBlocklistManager.convertToRawUrl(input))
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
    fun `failed attempts back off exponentially before retrying`() = runTest {
        val bundle = bundleOf("https://a.example/flaky.json")
        var calls = 0
        val start = System.currentTimeMillis()
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100, retryBackoffUnit = 20) {
            calls++
            if (calls < 3) throw Exception("HTTP 503") else memberJson
        }
        val elapsed = System.currentTimeMillis() - start
        // Recovers on the 3rd attempt—proves retries aren’t just counted, they happen
        assertEquals(3, calls)
        assertEquals(1, resolved.metadata.importsLoaded)
        // Backoff before attempt 2 is 2 * 20 = 40 ms, before attempt 3 is 4 * 20 = 80 ms—
        // at least 120 ms of real delay if the backoff is actually being awaited
        assertTrue("expected real backoff delays (>=110ms), took ${elapsed}ms", elapsed >= 110)
    }

    @Test
    fun `failing imports are skipped with a warning`() = runTest {
        val bundle = bundleOf("https://a.example/ok1.json", "https://a.example/dead.json", "https://a.example/ok2.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100, retryBackoffUnit = 1) { url ->
            if (url.contains("dead")) throw Exception("HTTP 404") else memberJson
        }
        assertEquals(3, resolved.metadata.imports)
        assertEquals(2, resolved.metadata.importsLoaded)
        assertEquals(setOf("import1_ads", "import3_ads"), resolved.groups.keys.toSet())
        assertTrue(resolved.warning!!.startsWith("1 of 3 imports skipped"))
        assertTrue(resolved.warning.contains("dead.json"))
    }

    @Test
    fun `invalid import URLs are skipped with a warning`() = runTest {
        val bundle = bundleOf("@@https://a.example/broken.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { memberJson }
        assertEquals(2, resolved.metadata.imports)
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("@@https://a.example/broken.json"))
    }

    @Test
    fun `duplicate imports are skipped with a warning`() = runTest {
        val bundle = bundleOf("https://a.example/ok.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { memberJson }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertEquals(setOf("import1_ads"), resolved.groups.keys.toSet())
        assertTrue(resolved.warning!!.contains("duplicate import"))
    }

    @Test
    fun `oversized imports are skipped before parsing`() = runTest {
        val huge = "x".repeat(10 * 1024 * 1024 + 1)
        val bundle = bundleOf("https://a.example/huge.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("huge")) huge else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("too large"))
    }

    @Test
    fun `imports are fetched via their normalized URL`() = runTest {
        val bundle = bundleOf("https://github.com/user/repo/blob/main/list.json")
        var fetchedUrl: String? = null
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            fetchedUrl = url
            memberJson
        }
        assertEquals("https://raw.githubusercontent.com/user/repo/main/list.json", fetchedUrl)
        assertEquals(1, resolved.metadata.importsLoaded)
    }

    @Test
    fun `skipped imports do not count toward the combined size limit`() = runTest {
        // An invalid member almost as large as the limit—if its size counted, the valid import would push the bundle over
        val bigInvalid = "x".repeat(10 * 1024 * 1024 - 50)
        val bundle = bundleOf("https://a.example/big-bad.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("big-bad")) bigInvalid else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("big-bad.json"))
    }

    @Test
    fun `resolution fails when no import can be loaded`() = runTest {
        val bundle = bundleOf("https://a.example/d1.json", "https://a.example/d2.json")
        try {
            ExternalBlocklistManager.resolveBundle(bundle, 100, retryBackoffUnit = 1) { throw Exception("HTTP 404") }
            fail("Expected resolution to fail")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("None of the imported blocklists could be loaded"))
        }
    }

    @Test
    fun `nested bundles are skipped with a warning`() = runTest {
        val bundle = bundleOf("https://a.example/nested.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("nested")) """{"imports": ["https://x.example/y.json"]}""" else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("is itself a bundle"))
    }

    @Test
    fun `invalid member JSON is skipped with a warning`() = runTest {
        val bundle = bundleOf("https://a.example/bad.json", "https://a.example/ok.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { url ->
            if (url.contains("bad")) "{invalid" else memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("bad.json"))
    }

    @Test
    fun `healthy bundles resolve without warning`() = runTest {
        val bundle = bundleOf("https://a.example/ok1.json", "https://a.example/ok2.json")
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100) { memberJson }
        assertEquals(null, resolved.warning)
        assertEquals(2, resolved.metadata.importsLoaded)
        assertEquals(2, resolved.metadata.totalRules)
    }

    @Test
    fun `resolution fails when the combined size limit is exceeded`() = runTest {
        val bundle = bundleOf("https://a.example/ok.json")
        try {
            ExternalBlocklistManager.resolveBundle(bundle, 10 * 1024 * 1024 - 10) { memberJson }
            fail("Expected resolution to fail")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Bundle too large"))
        }
    }

    @Test
    fun `imports are fetched concurrently, not one at a time`() = runTest {
        val bundle = bundleOf(
            "https://a.example/c1.json", "https://a.example/c2.json", "https://a.example/c3.json",
            "https://a.example/c4.json", "https://a.example/c5.json"
        )
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100, concurrency = 5) {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, current) }
            Thread.sleep(80)
            inFlight.decrementAndGet()
            memberJson
        }
        assertEquals(5, resolved.metadata.importsLoaded)
        // If fetches ran one at a time, max in-flight would be 1
        assertTrue("expected concurrent fetches, saw max in-flight = ${maxInFlight.get()}", maxInFlight.get() > 1)
        assertTrue("concurrency cap of 5 was exceeded: ${maxInFlight.get()}", maxInFlight.get() <= 5)
    }

    @Test
    fun `a hanging import is interrupted at the fetch timeout, not left to run`() = runTest {
        val bundle = bundleOf("https://a.example/hang.json", "https://a.example/ok.json")
        val start = System.currentTimeMillis()
        val resolved = ExternalBlocklistManager.resolveBundle(
            bundle, 100, concurrency = 2, importFetchTimeoutMs = 50, retryBackoffUnit = 1
        ) { url ->
            if (url.contains("hang")) {
                Thread.sleep(2000) // would dominate the test unless actually interrupted
            }
            memberJson
        }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("timed out"))
        // Three attempts at ~50 ms each (plus a negligible 1ms-unit backoff) should
        // finish in well under the 2-second sleep if the blocking call is actually
        // being interrupted
        assertTrue("expected the hang to be interrupted near the timeout, took ${elapsed}ms", elapsed < 1000)
    }

    @Test
    fun `a slow fetch and a never-attempted import report distinct reasons`() = runTest {
        // “concurrency = 1” forces sequential batches: ok.json succeeds fast, slow.json
        // is slow enough to hit its own fetch timeout, and that alone exceeds the
        // overall deadline, so never.json is never attempted at all—these are two
        // different failures and should read differently, not both as generic “timed out”
        val bundle = bundleOf("https://a.example/ok.json", "https://a.example/slow.json", "https://a.example/never.json")
        val resolved = ExternalBlocklistManager.resolveBundle(
            bundle, 100, concurrency = 1, deadline = System.currentTimeMillis() + 80,
            importFetchTimeoutMs = 50, retryBackoffUnit = 1
        ) { url ->
            if (url.contains("slow")) Thread.sleep(200)
            memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        val failures = resolved.warning!!
        assertTrue("expected slow.json to report a fetch timeout: $failures", failures.contains("slow.json (timed out)"))
        assertTrue("expected never.json to report the budget, not a fetch timeout: $failures", failures.contains("never.json (time budget exceeded)"))
    }

    @Test
    fun `imports past the deadline are skipped as time budget exceeded`() = runTest {
        val bundle = bundleOf("https://a.example/slow1.json", "https://a.example/slow2.json")
        // “concurrency = 1” forces two sequential batches; the first eats the whole
        // budget, so the deadline check before the second batch should skip it
        val resolved = ExternalBlocklistManager.resolveBundle(bundle, 100, concurrency = 1, deadline = System.currentTimeMillis() + 50) {
            Thread.sleep(150)
            memberJson
        }
        assertEquals(1, resolved.metadata.importsLoaded)
        assertTrue(resolved.warning!!.contains("time budget exceeded"))
    }

    @Test
    fun `bundle size is counted in UTF-8 bytes`() = runTest {
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