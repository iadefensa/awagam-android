// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import com.awagam.android.data.blocklist.BlocklistValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Entry validation cases shared with AWAGAM Chromium (test/fixtures/entry-cases.json there).
 * Runs on Robolectric for the platform’s UTS #46 IDNA implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EntryCasesTest {

    private val fixture = javaClass.classLoader!!.getResource("entry-cases.json")!!.readText()
    private val cases = Json.parseToJsonElement(fixture).jsonObject

    private fun assertCases(field: String, validate: (String) -> Boolean) {
        val mismatches = cases.getValue(field).jsonArray.map { it.jsonObject }.mapNotNull { case ->
            val entry = case.getValue("entry").jsonPrimitive.content
            val valid = case.getValue("valid").jsonPrimitive.boolean
            if (validate(entry) != valid) "“$entry” should be ${if (valid) "valid" else "invalid"}" else null
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun `isValidTld follows the shared TLD cases`() = assertCases("tlds", BlocklistValidator::isValidTld)

    @Test
    fun `isValidDomain follows the shared domain cases`() = assertCases("domains", BlocklistValidator::isValidDomain)

    @Test
    fun `isValidBlocklistEntry follows the shared URL cases`() = assertCases("urls", BlocklistValidator::isValidBlocklistEntry)

    @Test
    fun `Shared cases match AWAGAM Chromium`() {
        // Gradle runs unit tests from the module directory
        val source = File(System.getenv("AWAGAM_CHROMIUM_DIR") ?: "../../awagam-chromium", "test/fixtures/entry-cases.json")
        assumeTrue("AWAGAM Chromium not found", source.isFile)
        assertEquals("entry-cases.json differs from AWAGAM Chromium’s", source.readText(), fixture)
    }
}