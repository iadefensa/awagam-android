package com.awagam.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awagam.android.di.DependencyContainer
import com.awagam.android.statistics.StatisticsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ViewModel for the statistics screen.
 * Provides access to DNS query statistics and formatting helpers.
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val statisticsManager = DependencyContainer.getStatisticsManager()

    val statisticsFlow: Flow<StatisticsManager.Statistics> = statisticsManager.statisticsFlow

    fun refreshStatistics() {
        statisticsManager.refresh()
    }

    fun formatUptime(milliseconds: Long): String {
        return statisticsManager.formatUptime(milliseconds)
    }

    fun formatDataUsage(bytes: Long): String {
        return statisticsManager.formatDataUsage(bytes)
    }

    fun resetSession() {
        viewModelScope.launch {
            statisticsManager.resetSession()
        }
    }

}