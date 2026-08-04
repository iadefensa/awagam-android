// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.awagam.android.data.blocklist.AwagamConfigExport
import com.awagam.android.data.blocklist.BlocklistExporter
import com.awagam.android.data.blocklist.BlocklistValidator
import com.awagam.android.data.blocklist.ExternalBlocklistConfig
import com.awagam.android.data.blocklist.ExternalBlocklistManager
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.di.DependencyContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val blocklists: List<ExternalBlocklistConfig> = emptyList(),
    val exportJson: String = "[]",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val exportContent: String? = null,
    val autoStart: Boolean = false
)

/**
 * ViewModel for the settings screen.
 * Manages blocklist configuration, import/export, and refresh operations.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val blocklistManager = ExternalBlocklistManager(application)
    private val blocklistRepository = DependencyContainer.getBlocklistRepository()
    private val userPreferences = UserPreferences(application)
    private val exporter = BlocklistExporter(application)
    // `encodeDefaults` so the export states every field explicitly, including
    // `updateInterval`: The browser extension honors per-list intervals and
    // substitutes its own 6-hour default for a missing one, which would refresh
    // imported lists four times as often as this app says it does
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _shareIntent = MutableStateFlow<Intent?>(null)
    val shareIntent: StateFlow<Intent?> = _shareIntent.asStateFlow()

    init {
        viewModelScope.launch {
            blocklistManager.blocklistsFlow.collect { blocklists ->
                // Create export in browser extension format
                val export = AwagamConfigExport(
                    version = "2.1.0",
                    exportedAt = getIsoTimestamp(),
                    externalBlocklists = blocklists.associateBy { it.id }
                )
                _uiState.update {
                    it.copy(
                        blocklists = blocklists,
                        exportJson = json.encodeToString(export)
                    )
                }
            }
        }

        viewModelScope.launch {
            userPreferences.autoStartFlow.collect { autoStart ->
                _uiState.update { it.copy(autoStart = autoStart) }
            }
        }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setAutoStart(enabled)
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun addBlocklist(name: String, url: String) {
        if (name.isBlank() || !url.startsWith("https://")) return

        viewModelScope.launch {
            val config = ExternalBlocklistConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                url = url.trim(),
                enabled = true
            )
            blocklistManager.addBlocklist(config)
            blocklistManager.refreshBlocklist(config.id)
        }
    }

    fun editBlocklist(id: String, name: String, url: String) {
        if (name.isBlank() || !url.startsWith("https://")) return

        viewModelScope.launch {
            val configs = blocklistManager.blocklistsFlow.first()
            val existing = configs.find { it.id == id } ?: return@launch
            val urlChanged = url.trim() != existing.url

            if (urlChanged && !BlocklistValidator.isValidBlocklistUrl(url.trim())) {
                _uiState.update {
                    it.copy(error = "Invalid or insecure URL. Only HTTPS URLs from public hosts are allowed.")
                }
                return@launch
            }

            blocklistManager.updateBlocklist(
                existing.copy(
                    name = name.trim(),
                    url = url.trim()
                )
            )

            if (urlChanged) {
                refreshBlocklist(id)
            }
        }
    }

    fun toggleBlocklist(id: String) {
        viewModelScope.launch {
            blocklistManager.toggleBlocklist(id)
        }
    }

    fun refreshBlocklist(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null, successMessage = null) }
            try {
                blocklistManager.refreshBlocklist(id)
                // Check if there was an error by looking at the updated config—
                // “warning” also carries an `errorMessage` (a bundle’s skipped
                // imports, shown on the card), but that refresh succeeded
                val configs = blocklistManager.blocklistsFlow.first()
                val config = configs.find { it.id == id }
                if (config?.status == "error") {
                    _uiState.update { it.copy(isRefreshing = false, error = "Failed: ${config.errorMessage}") }
                } else if (config?.status == "warning") {
                    _uiState.update { it.copy(isRefreshing = false, successMessage = "Blocklist refreshed—some imports were skipped") }
                } else {
                    _uiState.update { it.copy(isRefreshing = false, successMessage = "Blocklist refreshed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = "Refresh failed: ${e.message}") }
            }
        }
    }

    fun refreshAllBlocklists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null, successMessage = null) }
            try {
                blocklistManager.refreshAllBlocklists()
                // Check for errors—“warning” status is a successful refresh
                val configs = blocklistManager.blocklistsFlow.first()
                val errors = configs.filter { it.enabled && it.status == "error" }
                if (errors.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = "${errors.size} blocklist(s) failed to refresh"
                        )
                    }
                } else {
                    val refreshedCount = configs.count { it.enabled }
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            successMessage = if (refreshedCount > 0) "$refreshedCount blocklist(s) refreshed" else "No blocklists to refresh"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = "Refresh failed: ${e.message}") }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }

    fun deleteBlocklist(id: String) {
        viewModelScope.launch {
            blocklistManager.deleteBlocklist(id)
        }
    }

    fun importConfiguration(jsonString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null, successMessage = null) }
            try {
                val configs = try {
                    // Try parsing as browser extension format first
                    val importJson = Json { ignoreUnknownKeys = true }
                    val export: AwagamConfigExport = importJson.decodeFromString(jsonString)
                    export.externalBlocklists.values.toList()
                } catch (e: Exception) {
                    // Try parsing as simple list (legacy format)
                    Json.decodeFromString<List<ExternalBlocklistConfig>>(jsonString)
                }
                blocklistManager.importBlocklists(configs)
                // Fetch content for all imported blocklists
                blocklistManager.refreshAllBlocklists()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        successMessage = "Imported and refreshed ${configs.size} blocklist(s)"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, error = "Invalid JSON format") }
            }
        }
    }

    /**
     * Generate export content for clipboard.
     */
    fun generateExport(format: BlocklistExporter.Format) {
        viewModelScope.launch {
            blocklistRepository.loadBlocklists()

            val domains = blocklistRepository.getBlockedDomains()
            val tlds = blocklistRepository.getBlockedTlds()
            val urls = blocklistRepository.getBlockedUrls()

            if (domains.isEmpty() && tlds.isEmpty() && urls.isEmpty()) {
                _uiState.update {
                    it.copy(error = "No entries to export. Make sure blocklists are refreshed successfully.")
                }
                return@launch
            }

            val content = exporter.getExportString(domains, tlds, urls, format)
            _uiState.update { it.copy(exportContent = content) }
        }
    }

    /**
     * Create share intent for exporting blocklist.
     */
    fun shareExport(format: BlocklistExporter.Format) {
        viewModelScope.launch {
            blocklistRepository.loadBlocklists()

            val domains = blocklistRepository.getBlockedDomains()
            val tlds = blocklistRepository.getBlockedTlds()
            val urls = blocklistRepository.getBlockedUrls()

            val intent = exporter.export(domains, tlds, urls, format)
            _shareIntent.value = intent
        }
    }

    fun clearShareIntent() {
        _shareIntent.value = null
    }

    fun clearExportContent() {
        _uiState.update { it.copy(exportContent = null) }
    }
}