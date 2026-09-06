package com.example.infrastructure.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real connectivity monitor (audit 2026 fix).
 *
 * Previously `MainViewModel.executePrompt` hardcoded `isNetworkAvailable = true`,
 * which made the entire OFFLINE policy unreachable in practice: cloud LLM/search
 * calls would hang against a dead network instead of honestly failing or
 * routing to local resources.
 *
 * This adapter reads the ACTUAL system state via ConnectivityManager and
 * exposes it as a StateFlow. It is the single source of truth for
 * `isNetworkAvailable` fed into the orchestrator and DecisionService.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isNetworkAvailable = MutableStateFlow(queryCurrentState())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkAvailable.value = true
        }

        override fun onLost(network: Network) {
            // Re-query instead of blindly setting false: another network
            // (e.g. WiFi → cellular handover) may still be up.
            _isNetworkAvailable.value = queryCurrentState()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isNetworkAvailable.value =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    fun start() {
        val cm = connectivityManager ?: return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // Callback registration can fail on exotic devices — the polled
            // state from queryCurrentState() remains usable.
        }
    }

    fun stop() {
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Not registered / already unregistered — nothing to do.
        }
    }

    private fun queryCurrentState(): Boolean {
        val cm = connectivityManager ?: return false
        val networks = cm.allNetworks ?: return false
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true
            }
        }
        return false
    }
}
