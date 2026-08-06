// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.awagam.android.data.blocklist.BlocklistMetadata
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import com.awagam.android.data.blocklist.deletionImpact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for what a blocklist deletion costs, and for the warning shown
 * before one is confirmed.
 * A bundle stands for the lists it imports, whose URLs the UI never shows, so
 * the confirmation has to say how many go with it—deleting one entry can drop
 * a dozen sources the user cannot list afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BlocklistDeletionTest {

    private fun config(
        id: String = "test-id",
        metadata: BlocklistMetadata? = null
    ) = ExternalBlocklistConfig(
        id = id,
        name = "Test",
        url = "https://example.com/blocklist.json",
        metadata = metadata
    )

    // Deletion Impact Tests

    @Test
    fun `a plain list warns about nothing beyond itself`() {
        assertNull(
            "A list that imports nothing costs only its own entry",
            config(metadata = BlocklistMetadata(totalRules = 10, domains = 10)).deletionImpact()
        )
    }

    @Test
    fun `a list never refreshed warns about nothing beyond itself`() {
        assertNull(
            "Metadata is absent until the first refresh, and absence is not a bundle",
            config(metadata = null).deletionImpact()
        )
    }

    @Test
    fun `a bundle warns with the number of lists that go with it`() {
        val impact = config(
            metadata = BlocklistMetadata(imports = 4, importsLoaded = 3)
        ).deletionImpact()

        assertNotNull("A bundle must warn before it is deleted", impact)
        assertTrue(
            "The warning must count the lists actually in effect, not those declared: $impact",
            impact!!.contains("3")
        )
    }

    @Test
    fun `a bundle whose imports all failed warns about nothing beyond itself`() {
        assertNull(
            "Nothing loaded means nothing extra is lost, whatever the bundle declared",
            config(metadata = BlocklistMetadata(imports = 4, importsLoaded = 0)).deletionImpact()
        )
    }

    // Deletion Tests

    @Test
    fun `deleting removes only the confirmed list`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = ExternalBlocklistManager(app)

        runBlocking {
            manager.addBlocklist(config(id = "keep-me"))
            manager.addBlocklist(config(id = "delete-me"))
            manager.deleteBlocklist("delete-me")

            val remaining = manager.blocklistsFlow.first()
            assertEquals("Only the confirmed list may go", 1, remaining.size)
            assertEquals("keep-me", remaining.first().id)
        }
    }
}
