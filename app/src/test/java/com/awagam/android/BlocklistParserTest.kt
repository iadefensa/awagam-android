package com.awagam.android

import com.awagam.android.data.blocklist.BlocklistGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for blocklist JSON parsing.
 * Ensures compatibility with AWAGAM browser extension format.
 */
class BlocklistParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `parse valid blocklist JSON with string context`() {
        val jsonString = """
            {
                "test-group": {
                    "name": "Test Group",
                    "context": "Test context",
                    "tlds": [".ru", ".cn"],
                    "domains": ["blocked.com", "example.org"],
                    "urls": ["example.com/path/*"]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

        assertEquals(1, groups.size)
        val group = groups["test-group"]!!

        assertEquals("Test Group", group.name)
        // Context is now JsonElement—verify it’s a string primitive
        assertTrue(group.context is JsonPrimitive)
        assertEquals("Test context", (group.context as JsonPrimitive).content)
        assertEquals(listOf(".ru", ".cn"), group.tlds)
        assertEquals(listOf("blocked.com", "example.org"), group.domains)
        assertEquals(listOf("example.com/path/*"), group.urls)
    }

    @Test
    fun `parse blocklist JSON with array context`() {
        val jsonString = """
            {
                "test-group": {
                    "name": "Test Group",
                    "context": ["https://en.wikipedia.org/wiki/Test", "Reference link"],
                    "tlds": [".ru"],
                    "domains": ["blocked.com"]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)
        val group = groups["test-group"]!!

        assertEquals("Test Group", group.name)
        // Context is now JsonElement - verify it's an array
        assertTrue(group.context is JsonArray)
        val contextArray = group.context as JsonArray
        assertEquals(2, contextArray.size)
    }

    @Test
    fun `parse blocklist JSON with null context`() {
        val jsonString = """
            {
                "test-group": {
                    "name": "Test Group",
                    "context": null,
                    "domains": ["blocked.com"]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)
        val group = groups["test-group"]!!

        assertEquals("Test Group", group.name)
        // When JSON has explicit null, `kotlinx.serialization` deserializes as JsonNull
        // but the nullable type may also just be null
        assertTrue(group.context == null || group.context is JsonNull)
    }

    @Test
    fun `parse blocklist with missing optional fields`() {
        val jsonString = """
            {
                "minimal": {
                    "name": "Minimal Group"
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

        val group = groups["minimal"]!!
        assertEquals("Minimal Group", group.name)
        assertNull(group.context)
        assertTrue(group.tlds.isEmpty())
        assertTrue(group.domains.isEmpty())
        assertTrue(group.urls.isEmpty())
    }

    @Test
    fun `parse blocklist with multiple groups`() {
        val jsonString = """
            {
                "group1": {
                    "name": "Group 1",
                    "domains": ["a.com"]
                },
                "group2": {
                    "name": "Group 2",
                    "tlds": [".xyz"]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

        assertEquals(2, groups.size)
        assertTrue(groups.containsKey("group1"))
        assertTrue(groups.containsKey("group2"))
    }

    @Test
    fun `parse empty blocklist`() {
        val jsonString = "{}"
        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `ignore unknown fields in JSON`() {
        val jsonString = """
            {
                "test": {
                    "name": "Test",
                    "unknownField": "should be ignored",
                    "anotherUnknown": 123,
                    "domains": ["test.com"]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)
        val group = groups["test"]!!

        assertEquals("Test", group.name)
        assertEquals(listOf("test.com"), group.domains)
    }

    @Test
    fun `parse real-world blocklist format`() {
        // Mimics actual AWAGAM blocklist structure
        val jsonString = """
            {
                "example-country": {
                    "name": "Example Country",
                    "context": ["https://example.org/source"],
                    "tlds": [
                        ".example"
                    ],
                    "domains": [
                        "example-domain.com",
                        "another-example.org"
                    ],
                    "urls": []
                },
                "advertisers": {
                    "name": "Ad Networks",
                    "domains": [
                        "ads.example.com",
                        "tracking.example.net"
                    ]
                }
            }
        """.trimIndent()

        val groups: Map<String, BlocklistGroup> = json.decodeFromString(jsonString)

        assertEquals(2, groups.size)

        val country = groups["example-country"]!!
        assertEquals(1, country.tlds.size)
        assertEquals(2, country.domains.size)

        val ads = groups["advertisers"]!!
        assertEquals(2, ads.domains.size)
        assertTrue(ads.tlds.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `reject invalid JSON`() {
        val invalidJson = "{ this is not valid json }"
        json.decodeFromString<Map<String, BlocklistGroup>>(invalidJson)
    }
}