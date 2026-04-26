package com.awagam.android

import com.awagam.android.data.blocklist.ExternalBlocklistManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for ExternalBlocklistManager.convertToRawUrl.
 * Verifies that hosting-platform blob URLs are correctly converted to direct download URLs.
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
}
