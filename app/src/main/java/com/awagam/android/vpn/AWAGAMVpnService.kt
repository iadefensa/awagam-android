// SPDX-License-Identifier: GPL-3.0-or-later

package com.awagam.android.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.awagam.android.AWAGAMApplication
import com.awagam.android.MainActivity
import com.awagam.android.R
import com.awagam.android.data.blocklist.BlocklistRepository
import com.awagam.android.data.preferences.UserPreferences
import com.awagam.android.di.DependencyContainer
import com.awagam.android.dns.DnsResolver
import com.awagam.android.statistics.StatisticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class AWAGAMVpnService : VpnService() {

    companion object {
        private const val TAG = "AWAGAMVpnService"
        const val ACTION_START = "com.awagam.android.START_VPN"
        const val ACTION_STOP = "com.awagam.android.STOP_VPN"

        // VPN configuration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_DNS = "10.0.0.1"
        private const val VPN_MTU = 1500

        // Upper bound on queries being resolved at once. Each in-flight query
        // holds a packet copy and can wait up to 5 s on a DoH call, so an app
        // flooding the tunnel would otherwise grow memory without limit and
        // saturate the OkHttp dispatcher, slowing down every other query
        private const val MAX_CONCURRENT_QUERIES = 32

        // Check whether VPN service is actively running;
        // volatile for cross-thread visibility
        @Volatile
        var isServiceRunning = false
            private set

        // True between `stopVpn()` and the async `setEnabled(false)` completing.
        // Prevents `onResume()` from restarting based on stale DataStore values.
        @Volatile
        var pendingStop = false
            private set

        // For work that has to finish after the service is destroyed
        private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupJob: Job? = null
    private var processingJob: Job? = null

    private lateinit var blocklistRepository: BlocklistRepository
    private lateinit var userPreferences: UserPreferences
    private lateinit var dnsResolver: DnsResolver
    private lateinit var statisticsManager: StatisticsManager

    override fun onCreate() {
        super.onCreate()
        blocklistRepository = DependencyContainer.getBlocklistRepository()
        userPreferences = DependencyContainer.getUserPreferences()
        dnsResolver = DependencyContainer.getDnsResolver()
        statisticsManager = DependencyContainer.getStatisticsManager()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startVpn()
                START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                // System restarted us after process death (`START_STICKY` delivers a null intent)
                // Resume the VPN so the user stays protected
                Log.d(TAG, "Service restarted by system (null intent), resuming VPN")
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d(TAG, "VPN already running")
            // A live tunnel with the preference still off leaves the toggle stuck:
            // Every tap lands here and returns, so nothing ever writes the
            // preference the switch reads. Re-assert it instead of returning silently
            if (vpnInterface != null) {
                pendingStop = false
                serviceScope.launch {
                    userPreferences.setEnabled(true)
                    userPreferences.clearVpnError()
                }
            }
            return
        }

        // Set early to prevent re-entry from concurrent callers
        // (e.g., permission callback and `onResume` firing together)
        isRunning = true
        pendingStop = false

        startupJob = serviceScope.launch {
            try {
                // Load blocklists before starting
                blocklistRepository.loadBlocklists()

                // Clear stale DNS cache from any prior session
                dnsResolver.clearCache()

                // Initialize DoH client with user’s preferred upstream
                val upstreamDns = userPreferences.upstreamDnsFlow.first()
                dnsResolver.setUpstreamDns(upstreamDns)

                // Set up protected socket factory so DoH requests bypass the VPN
                dnsResolver.setProtectedSocketFactory(createProtectedSocketFactory())

                // Start foreground service with notification
                startForeground(AWAGAMApplication.NOTIFICATION_ID, createNotification())

                // Bail out if stop was requested during initialization
                ensureActive()

                // Configure and establish VPN (non-suspending, so check afterwards)
                val iface = establishVpn()

                // If stop was requested during establishment, close and abort
                if (!isActive) {
                    iface?.close()
                    return@launch
                }

                if (iface != null) {
                    vpnInterface = iface
                    isServiceRunning = true
                    userPreferences.setEnabled(true)
                    userPreferences.clearVpnError()
                    startPacketProcessing()
                    Log.d(TAG, "VPN started successfully")

                    // Test DoH connectivity in the background and warn the user if it fails
                    launch(Dispatchers.IO) {
                        val error = dnsResolver.testUpstreamConnectivity()
                        if (error != null) {
                            Log.e(TAG, "DoH connectivity test failed: $error")
                            userPreferences.setVpnError(
                                "${UserPreferences.VPN_ERROR_DOH_FAILED}:$error"
                            )
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to establish VPN interface (another VPN may be active)")
                    failStartup(UserPreferences.VPN_ERROR_ANOTHER_VPN)
                }
            } catch (e: CancellationException) {
                isRunning = false
                Log.d(TAG, "VPN startup canceled")
                // `stopVpn()` closes `vpnInterface`, but a cancellation landing
                // between the assignment above and the preference write finds it
                // still null there—close it here so no tunnel outlives the stop
                try {
                    vpnInterface?.close()
                } catch (closeError: Exception) {
                    Log.e(TAG, "Failed to close VPN interface", closeError)
                }
                vpnInterface = null
                throw e
            } catch (e: Throwable) {
                // `Throwable`, not `Exception`: an `Error` (out of memory while
                // parsing a large blocklist, say) would otherwise leave
                // `isRunning` true, and every later start request would
                // short-circuit on it—the toggle would never move again
                Log.e(TAG, "Failed to start VPN", e)
                failStartup(UserPreferences.VPN_ERROR_GENERAL)
            }
        }
    }

    /**
     * Report a failed start and tear down.
     * The preference writes are guarded because failing to persist them must not
     * skip the teardown: that would strand an open tunnel behind a UI that says
     * protection is off, with no way to recover from the toggle.
     */
    private suspend fun failStartup(error: String) {
        isRunning = false
        isServiceRunning = false

        try {
            withContext(NonCancellable) {
                userPreferences.setVpnError(error)
                userPreferences.setEnabled(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist VPN error state", e)
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close VPN interface", e)
        }
        vpnInterface = null

        stopSelf()
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("AWAGAM")
                .addAddress(VPN_ADDRESS, 32)
                // Route only the VPN DNS server through the tunnel so that DNS queries
                // are intercepted while all other traffic bypasses the VPN entirely
                .addRoute(VPN_DNS, 32)
                .addDnsServer(VPN_DNS)
                .setMtu(VPN_MTU)
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            null
        }
    }

    /**
     * Creates a socket factory that protects sockets from being routed through the VPN.
     * This is essential for DoH requests to avoid infinite loops.
     *
     * Important: `protect()` must be called before the socket connects.
     * Socket constructors with host/port connect immediately, so we must:
     * 1. Create an unconnected socket
     * 2. Protect it
     * 3. Connect it
     */
    private fun createProtectedSocketFactory(): SocketFactory {
        val vpnService = this
        return object : SocketFactory() {
            override fun createSocket(): Socket {
                return Socket().also { vpnService.protect(it) }
            }

            override fun createSocket(host: String?, port: Int): Socket {
                // Create unconnected, protect, then connect
                val socket = Socket()
                vpnService.protect(socket)
                socket.connect(java.net.InetSocketAddress(host, port))
                return socket
            }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val socket = Socket()
                vpnService.protect(socket)
                socket.bind(java.net.InetSocketAddress(localHost, localPort))
                socket.connect(java.net.InetSocketAddress(host, port))
                return socket
            }

            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val socket = Socket()
                vpnService.protect(socket)
                socket.connect(java.net.InetSocketAddress(host, port))
                return socket
            }

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                val socket = Socket()
                vpnService.protect(socket)
                socket.bind(java.net.InetSocketAddress(localAddress, localPort))
                socket.connect(java.net.InetSocketAddress(address, port))
                return socket
            }
        }
    }

    private fun startPacketProcessing() {
        val vpn = vpnInterface ?: return

        processingJob = serviceScope.launch {
            val inputStream = FileInputStream(vpn.fileDescriptor)
            val outputStream = FileOutputStream(vpn.fileDescriptor)
            val outputLock = Any()
            val packet = ByteArray(VPN_MTU)
            val inFlight = Semaphore(MAX_CONCURRENT_QUERIES)

            while (isRunning) {
                try {
                    val length = inputStream.read(packet)
                    if (length > 0) {
                        // Drop the query instead of queuing it once the limit is
                        // reached; DNS clients retry, and answering late is worse
                        // than not answering at all
                        if (!inFlight.tryAcquire()) {
                            Log.w(TAG, "Dropping query: $MAX_CONCURRENT_QUERIES already in flight")
                            continue
                        }
                        // Copy packet data so the buffer can be reused immediately
                        val packetCopy = packet.copyOfRange(0, length)
                        // Process each query concurrently so a slow DoH request
                        // doesn’t block all other DNS queries
                        launch {
                            try {
                                val response = processPacket(packetCopy, length)
                                if (response != null) {
                                    synchronized(outputLock) {
                                        outputStream.write(response)
                                    }
                                }
                            } catch (e: Exception) {
                                if (isRunning) {
                                    Log.e(TAG, "Error processing packet", e)
                                }
                            } finally {
                                inFlight.release()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error reading packet", e)
                    }
                }
            }
        }
    }

    private suspend fun processPacket(packet: ByteArray, length: Int): ByteArray? {
        if (isDnsPacket(packet, length)) {
            return try {
                dnsResolver.resolve(packet, length)
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving DNS", e)
                null
            }
        }

        // Reject DNS-over-TLS probes (TCP SYN to port 853) with a RST so that
        // Android's Private DNS "Automatic" mode falls back to plain DNS immediately
        // instead of waiting for a connection timeout.
        if (DnsResolver.isTcpSynToPort853(packet, length)) {
            return DnsResolver.createTcpRst(packet, length)
        }

        return null
    }

    private fun isDnsPacket(packet: ByteArray, length: Int): Boolean {
        // Minimum IP + UDP header size
        if (length < 28) return false

        // Check IP version (should be IPv4)
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return false

        // Check protocol (should be UDP = 17)
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false

        // Check destination port (should be 53 for DNS)
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        return destPort == 53
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping VPN")
        isRunning = false
        isServiceRunning = false
        pendingStop = true

        // Cancel any in-progress startup so it cannot overwrite
        // the disabled preference or establish a tunnel after stop
        startupJob?.cancel()
        startupJob = null

        processingJob?.cancel()
        processingJob = null

        vpnInterface?.close()
        vpnInterface = null

        // Write the preference asynchronously to avoid blocking the main thread,
        // which deadlocks with concurrent DataStore edits from the ViewModel
        // (DataStore dispatches transforms via `withContext(callerContext)` and the
        // ViewModel’s `callerContext` is `Dispatchers.Main`).
        // The scope outlives the service instance: `stopSelf()` below triggers
        // `onDestroy()`, and a coroutine launched on `serviceScope` that has not
        // started yet when that cancels it never runs its body at all—`pendingStop`
        // would stay set for the process lifetime, blocking every later restart.
        shutdownScope.launch {
            withContext(NonCancellable) {
                userPreferences.setEnabled(false)
                pendingStop = false
                // Persist the queries recorded since the last periodic flush
                statisticsManager.flush()
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun createNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stats = blocklistRepository.blocklistStats.first()
        val totalRules = stats.tldCount + stats.domainCount

        return NotificationCompat.Builder(this, AWAGAMApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text, totalRules))
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        isServiceRunning = false
        statisticsManager.flushNow()
        vpnInterface?.close()
        vpnInterface = null
        serviceScope.cancel()
        super.onDestroy()
    }
}