package com.awagam.android

import com.awagam.android.data.blocklist.BlocklistGroup
import com.awagam.android.data.blocklist.BlocklistValidator
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BlocklistValidator.
 * Tests URL validation, TLD/domain format validation, and security checks.
 */
class BlocklistValidatorTest {

    // URL Validation Tests

    @Test
    fun `valid HTTPS URLs are accepted`() {
        assertTrue(BlocklistValidator.isValidBlocklistUrl("https://example.com/blocklist.json"))
        assertTrue(BlocklistValidator.isValidBlocklistUrl("https://raw.githubusercontent.com/user/repo/main/file.json"))
        assertTrue(BlocklistValidator.isValidBlocklistUrl("https://gitlab.com/user/repo/-/raw/main/file.json"))
    }

    @Test
    fun `HTTP URLs are rejected`() {
        assertFalse(BlocklistValidator.isValidBlocklistUrl("http://example.com/blocklist.json"))
    }

    @Test
    fun `localhost URLs are rejected`() {
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://localhost/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://127.0.0.1/blocklist.json"))
    }

    @Test
    fun `private IP ranges are rejected`() {
        // 10.0.0.0/8
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://10.0.0.1/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://10.255.255.255/blocklist.json"))

        // 172.16.0.0/12
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://172.16.0.1/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://172.31.255.255/blocklist.json"))

        // 192.168.0.0/16
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://192.168.1.1/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://192.168.0.100/blocklist.json"))
    }

    @Test
    fun `internal domains are rejected`() {
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://myserver.local/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://server.internal/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://server.corp/blocklist.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("https://router.home/blocklist.json"))
    }

    // Bundle Validation Tests

    @Test
    fun `bundles are detected by imports array`() {
        val bundle = Json.parseToJsonElement("""{"imports": ["https://example.com/a.json"]}""")
        assertTrue(BlocklistValidator.isBundle(bundle))

        // A blocklist group named “imports” is an object, not an array
        val blocklist = Json.parseToJsonElement("""{"imports": {"name": "Not a bundle"}}""")
        assertFalse(BlocklistValidator.isBundle(blocklist))

        val plain = Json.parseToJsonElement("""{"ads": {"name": "Ads", "domains": ["ads.example"]}}""")
        assertFalse(BlocklistValidator.isBundle(plain))
    }

    @Test
    fun `valid bundles are accepted`() {
        val bundle = Json.parseToJsonElement(
            """{"imports": ["https://example.com/a.json", "https://example.com/b.json"]}"""
        )
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertTrue(result.valid)
        assertEquals(
            listOf("https://example.com/a.json", "https://example.com/b.json"),
            result.imports
        )
    }

    @Test
    fun `bundles with extra fields are rejected`() {
        val bundle = Json.parseToJsonElement(
            """{"imports": ["https://example.com/a.json"], "name": "Extra"}"""
        )
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `empty bundles are rejected`() {
        val bundle = Json.parseToJsonElement("""{"imports": []}""")
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `bundles with insecure import URLs are rejected`() {
        val bundle = Json.parseToJsonElement("""{"imports": ["http://example.com/a.json"]}""")
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `bundles with duplicate import URLs are rejected`() {
        val bundle = Json.parseToJsonElement(
            """{"imports": ["https://example.com/a.json", "https://example.com/a.json"]}"""
        )
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `bundles with duplicate imports via different URL representations are rejected`() {
        val bundle = Json.parseToJsonElement(
            """{"imports": ["https://github.com/user/repo/blob/main/list.json", "https://raw.githubusercontent.com/user/repo/main/list.json"]}"""
        )
        val result = BlocklistValidator.validateBundleFormat(bundle) {
            ExternalBlocklistManager.convertToRawUrl(it)
        }
        assertFalse(result.valid)
    }

    @Test
    fun `bundles with too many imports are rejected`() {
        val urls = (1..101).joinToString(", ") { "\"https://example.com/$it.json\"" }
        val bundle = Json.parseToJsonElement("""{"imports": [$urls]}""")
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `bundles with non-string imports are rejected`() {
        val bundle = Json.parseToJsonElement("""{"imports": [42]}""")
        val result = BlocklistValidator.validateBundleFormat(bundle)
        assertFalse(result.valid)
    }

    @Test
    fun `invalid URLs are rejected`() {
        assertFalse(BlocklistValidator.isValidBlocklistUrl("not-a-url"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl("ftp://example.com/file.json"))
        assertFalse(BlocklistValidator.isValidBlocklistUrl(""))
    }

    // TLD Validation Tests

    @Test
    fun `valid TLDs are accepted`() {
        assertTrue(BlocklistValidator.isValidTld(".com"))
        assertTrue(BlocklistValidator.isValidTld(".ru"))
        assertTrue(BlocklistValidator.isValidTld(".co.uk"))
        assertTrue(BlocklistValidator.isValidTld(".com.au"))
        assertTrue(BlocklistValidator.isValidTld(".xn--p1ai")) // Punycode for .рф
    }

    @Test
    fun `TLDs without leading dot are rejected`() {
        assertFalse(BlocklistValidator.isValidTld("com"))
        assertFalse(BlocklistValidator.isValidTld("ru"))
    }

    @Test
    fun `invalid TLDs are rejected`() {
        assertFalse(BlocklistValidator.isValidTld("."))
        assertFalse(BlocklistValidator.isValidTld(".-invalid"))
        assertFalse(BlocklistValidator.isValidTld(".invalid-"))
        // Note: consecutive hyphens are valid in DNS (used in punycode xn--)
        // Only leading/trailing hyphens are invalid per RFC 1035
    }

    // Domain Validation Tests

    @Test
    fun `valid domains are accepted`() {
        assertTrue(BlocklistValidator.isValidDomain("example.com"))
        assertTrue(BlocklistValidator.isValidDomain("sub.example.com"))
        assertTrue(BlocklistValidator.isValidDomain("deep.sub.example.com"))
        assertTrue(BlocklistValidator.isValidDomain("example-site.com"))
        assertTrue(BlocklistValidator.isValidDomain("123.example.com"))
    }

    @Test
    fun `IDN domains are accepted`() {
        assertTrue(BlocklistValidator.isValidDomain("münchen.de"))
        assertTrue(BlocklistValidator.isValidDomain("xn--mnchen-3ya.de")) // Punycode
        assertTrue(BlocklistValidator.isValidDomain("example.рф"))
    }

    @Test
    fun `partial IP patterns are accepted for range blocking`() {
        assertTrue(BlocklistValidator.isValidDomain("192.168."))
        assertTrue(BlocklistValidator.isValidDomain("10.0."))
        assertTrue(BlocklistValidator.isValidDomain("142.91.159."))
    }

    @Test
    fun `invalid domains are rejected`() {
        assertFalse(BlocklistValidator.isValidDomain(""))
        assertFalse(BlocklistValidator.isValidDomain("-invalid.com"))
        assertFalse(BlocklistValidator.isValidDomain("invalid-.com"))
        assertFalse(BlocklistValidator.isValidDomain("invalid..com"))
    }

    // DNS Label Validation Tests

    @Test
    fun `valid DNS labels are accepted`() {
        assertTrue(BlocklistValidator.isValidDnsLabel("example"))
        assertTrue(BlocklistValidator.isValidDnsLabel("ex-ample"))
        assertTrue(BlocklistValidator.isValidDnsLabel("123"))
        assertTrue(BlocklistValidator.isValidDnsLabel("a1b2c3"))
    }

    @Test
    fun `DNS labels with invalid characters are rejected`() {
        assertFalse(BlocklistValidator.isValidDnsLabel("invalid_label"))
        assertFalse(BlocklistValidator.isValidDnsLabel("invalid label"))
        assertFalse(BlocklistValidator.isValidDnsLabel("invalid.label"))
    }

    @Test
    fun `DNS labels starting or ending with hyphen are rejected`() {
        assertFalse(BlocklistValidator.isValidDnsLabel("-invalid"))
        assertFalse(BlocklistValidator.isValidDnsLabel("invalid-"))
    }

    @Test
    fun `DNS labels exceeding 63 characters are rejected`() {
        val longLabel = "a".repeat(64)
        assertFalse(BlocklistValidator.isValidDnsLabel(longLabel))

        val validLabel = "a".repeat(63)
        assertTrue(BlocklistValidator.isValidDnsLabel(validLabel))
    }

    // Blocklist Entry (URL) Validation Tests

    @Test
    fun `valid blocklist URL entries are accepted`() {
        assertTrue(BlocklistValidator.isValidBlocklistEntry("https://example.com/path"))
        assertTrue(BlocklistValidator.isValidBlocklistEntry("http://example.com/path"))
        assertTrue(BlocklistValidator.isValidBlocklistEntry("example.com/path"))
        assertTrue(BlocklistValidator.isValidBlocklistEntry("example.com/path/*"))
    }

    @Test
    fun `blocklist entries with whitespace are rejected`() {
        assertFalse(BlocklistValidator.isValidBlocklistEntry("example.com/path with spaces"))
        assertFalse(BlocklistValidator.isValidBlocklistEntry(" example.com/path"))
    }

    // JSON Depth Validation Tests

    @Test
    fun `shallow JSON passes depth validation`() {
        val shallow = JsonObject(mapOf(
            "key" to JsonPrimitive("value")
        ))
        assertTrue(BlocklistValidator.validateJsonDepth(shallow))
    }

    @Test
    fun `nested JSON within limits passes`() {
        // Create a JSON object with depth 10
        var current: kotlinx.serialization.json.JsonElement = JsonPrimitive("value")
        for (i in 1..10) {
            current = JsonObject(mapOf("level$i" to current))
        }
        assertTrue(BlocklistValidator.validateJsonDepth(current))
    }

    // Size Validation Tests

    @Test
    fun `content within size limit passes`() {
        val smallContent = "a".repeat(1000)
        assertTrue(BlocklistValidator.validateSize(smallContent))
    }

    @Test
    fun `content exceeding size limit fails`() {
        val largeContent = "a".repeat(11 * 1024 * 1024) // 11 MB
        assertFalse(BlocklistValidator.validateSize(largeContent))
    }

    // Blocklist Format Validation Tests

    @Test
    fun `valid blocklist format passes`() {
        val groups = mapOf(
            "test" to BlocklistGroup(
                name = "Test Group",
                tlds = listOf(".ru", ".cn"),
                domains = listOf("blocked.com"),
                urls = listOf("example.com/path")
            )
        )

        val result = BlocklistValidator.validateBlocklistFormat(groups)
        assertTrue(result.valid)
        assertNotNull(result.metadata)
        assertEquals(2, result.metadata?.tlds)
        assertEquals(1, result.metadata?.domains)
        assertEquals(1, result.metadata?.urls)
        assertEquals(4, result.metadata?.totalRules)
    }

    @Test
    fun `blocklist with invalid TLD fails validation`() {
        val groups = mapOf(
            "test" to BlocklistGroup(
                name = "Test Group",
                tlds = listOf("invalid-tld") // Missing leading dot
            )
        )

        val result = BlocklistValidator.validateBlocklistFormat(groups)
        assertFalse(result.valid)
        assertTrue(result.error?.contains("Invalid TLD") == true)
    }

    @Test
    fun `blocklist with invalid domain fails validation`() {
        val groups = mapOf(
            "test" to BlocklistGroup(
                name = "Test Group",
                domains = listOf("-invalid.com")
            )
        )

        val result = BlocklistValidator.validateBlocklistFormat(groups)
        assertFalse(result.valid)
        assertTrue(result.error?.contains("Invalid domain") == true)
    }

    @Test
    fun `blocklist with missing name fails validation`() {
        val groups = mapOf(
            "test" to BlocklistGroup(
                name = "", // Empty name
                domains = listOf("example.com")
            )
        )

        val result = BlocklistValidator.validateBlocklistFormat(groups)
        assertFalse(result.valid)
        assertTrue(result.error?.contains("missing required") == true)
    }

    // Config Sanitization Tests

    @Test
    fun `config name is truncated and sanitized`() {
        val longName = "a".repeat(300) + "<script>alert('xss')</script>"
        val config = ExternalBlocklistConfig(
            id = "test",
            name = longName,
            url = "https://example.com/blocklist.json"
        )

        val sanitized = BlocklistValidator.sanitizeConfig(config)
        assertTrue(sanitized.name.length <= 200)
        assertFalse(sanitized.name.contains("<"))
        assertFalse(sanitized.name.contains(">"))
    }

    @Test
    fun `error message is truncated and sanitized`() {
        val longError = "Error: " + "a".repeat(600) + "<script>bad</script>"
        val config = ExternalBlocklistConfig(
            id = "test",
            name = "Test",
            url = "https://example.com/blocklist.json",
            errorMessage = longError
        )

        val sanitized = BlocklistValidator.sanitizeConfig(config)
        assertTrue((sanitized.errorMessage?.length ?: 0) <= 500)
        assertFalse(sanitized.errorMessage?.contains("<") == true)
    }

    // Note: Base64 decoding test requires Android environment (instrumented test)
    // The decodeBase64 function is tested via integration tests
}
