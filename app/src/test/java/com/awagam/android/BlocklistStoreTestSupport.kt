// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android

import android.content.Context
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Empty the blocklist store, which is one process-wide DataStore that every test
 * in the JVM shares—Gradle runs them all in one process, and the store outlives
 * any single class.
 * Call from `@Before`, so what an earlier class left behind cannot decide what a
 * wait here sees, and from `@After`, so this class leaves nothing for the next.
 * A test that only reads a count would otherwise pass or fail on the order the
 * classes happen to run in, and one waiting on a non-empty store would be
 * satisfied by someone else’s leftovers before its own write lands.
 */
fun clearStoredBlocklists(context: Context) {
    runBlocking {
        val manager = ExternalBlocklistManager(context)
        manager.blocklistsFlow.first().forEach { manager.deleteBlocklist(it.id) }
    }
}
