package com.awagam.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manages user preferences using DataStore.
 * Handles VPN state, auto-start, upstream DNS, and error tracking.
 */
class UserPreferences(private val context: Context) {

    companion object {
        private val IS_ENABLED = booleanPreferencesKey("is_enabled")
        private val AUTO_START = booleanPreferencesKey("auto_start")
        private val UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        private val DISABLE_UNTIL = longPreferencesKey("disable_until")
        private val VPN_ERROR = stringPreferencesKey("vpn_error")
        private val BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_prompt_dismissed")

        const val DNS_DNS4EU = "https://protective.joindns4.eu/dns-query"
        const val DNS_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DNS_GOOGLE = "https://dns.google/dns-query"
        const val DNS_QUAD9 = "https://dns.quad9.net/dns-query"

        const val VPN_ERROR_ANOTHER_VPN = "another_vpn_active"
        const val VPN_ERROR_DOH_FAILED = "doh_failed"
        const val VPN_ERROR_GENERAL = "general_error"
    }

    val isEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_ENABLED] ?: false
        }

    val autoStartFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_START] ?: false
        }

    val upstreamDnsFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[UPSTREAM_DNS] ?: DNS_DNS4EU
        }

    val disableUntilFlow: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[DISABLE_UNTIL] ?: 0L
        }

    val vpnErrorFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[VPN_ERROR]
        }

    val batteryPromptDismissedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BATTERY_PROMPT_DISMISSED] ?: false
        }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_ENABLED] = enabled
        }
    }

    suspend fun setAutoStart(autoStart: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_START] = autoStart
        }
    }

    suspend fun setUpstreamDns(url: String) {
        context.dataStore.edit { preferences ->
            preferences[UPSTREAM_DNS] = url
        }
    }

    suspend fun setTemporaryDisable(disableUntilMillis: Long) {
        context.dataStore.edit { preferences ->
            preferences[DISABLE_UNTIL] = disableUntilMillis
        }
    }

    suspend fun clearTemporaryDisable() {
        context.dataStore.edit { preferences ->
            preferences.remove(DISABLE_UNTIL)
        }
    }

    suspend fun setVpnError(error: String?) {
        context.dataStore.edit { preferences ->
            if (error != null) {
                preferences[VPN_ERROR] = error
            } else {
                preferences.remove(VPN_ERROR)
            }
        }
    }

    suspend fun clearVpnError() {
        setVpnError(null)
    }

    suspend fun setBatteryPromptDismissed(dismissed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BATTERY_PROMPT_DISMISSED] = dismissed
        }
    }
}
