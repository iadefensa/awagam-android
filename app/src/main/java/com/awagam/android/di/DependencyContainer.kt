// SPDX-FileCopyrightText: 2026 Jens Oliver Meiert (IA Defensa)
// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.di

import android.content.Context
import com.awagam.android.data.blocklist.BlocklistRepository
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.dns.DnsResolver
import com.awagam.android.statistics.StatisticsManager

/**
 * Simple dependency injection container.
 * Provides singleton instances of core components.
 */
object DependencyContainer {

    private var applicationContext: Context? = null
    private var blocklistRepository: BlocklistRepository? = null
    private var userPreferences: UserPreferences? = null
    private var dnsResolver: DnsResolver? = null
    private var statisticsManager: StatisticsManager? = null

    /**
     * Initialize the dependency container with application context.
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Get or create BlocklistRepository instance.
     */
    fun getBlocklistRepository(): BlocklistRepository {
        val context = applicationContext ?: throw IllegalStateException("DependencyContainer not initialized")
        return blocklistRepository ?: BlocklistRepository(context).also {
            blocklistRepository = it
        }
    }

    /**
     * Get or create UserPreferences instance.
     */
    fun getUserPreferences(): UserPreferences {
        val context = applicationContext ?: throw IllegalStateException("DependencyContainer not initialized")
        return userPreferences ?: UserPreferences(context).also {
            userPreferences = it
        }
    }

    /**
     * Get or create DnsResolver instance.
     */
    fun getDnsResolver(): DnsResolver {
        return dnsResolver ?: run {
            val blocklistRepo = getBlocklistRepository()
            val statsManager = getStatisticsManager()
            DnsResolver(blocklistRepo).also {
                it.initialize(statsManager)
                dnsResolver = it
            }
        }
    }

    /**
     * Get or create StatisticsManager instance.
     */
    fun getStatisticsManager(): StatisticsManager {
        val context = applicationContext ?: throw IllegalStateException("DependencyContainer not initialized")
        return statisticsManager ?: StatisticsManager(context).also {
            statisticsManager = it
        }
    }

    /**
     * Clear all dependencies (for testing).
     */
    fun clear() {
        // Stop the statistics flush loop before dropping the instance, so a
        // discarded manager can’t write after its replacement has taken over
        statisticsManager?.close()
        blocklistRepository = null
        userPreferences = null
        dnsResolver = null
        statisticsManager = null
        applicationContext = null
    }
}