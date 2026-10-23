package com.example.neartalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.location.LocationManagerCompat
import java.util.ArrayDeque
import java.util.UUID

private const val LOG_TAG = "NearTalkWifi"

@SuppressLint("MissingPermission")
class WifiDirectBroadcasts(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var generation = 0
    private var publishing = false
    private val advertisements = ArrayDeque<WifiP2pDnsSdServiceInfo>()
    private val seen = LinkedHashSet<String>()
    private val rescan = Runnable { scan() }
    private var requestService: WifiP2pDnsSdServiceInfo? = null
    private var requestId: String? = null

    val chat = WifiDirectChat(context,
        onDiscoveryPaused = { paused ->
            handler.removeCallbacks(rescan)
            canSend = false
            if (paused) status = "Discovery paused during the private connection."
            else if (channel != null) handler.postDelayed(rescan, 500)
        },
        onOfferClosed = { closeOffer() }
    )

    var messages by mutableStateOf<List<BroadcastItem>>(emptyList())
        private set
    var status by mutableStateOf("Tap Scan to enable nearby announcements.")
        private set
    var canSend by mutableStateOf(false)
        private set

    val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun hasPermissions() = permissions.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun unavailableReason(): String? {
        if (manager == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return "Wi-Fi Direct is not supported."
        }
        if (!hasPermissions()) return "Tap Scan and allow nearby discovery (Location on Android 8–12)."
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) return "Turn on Wi-Fi, then tap Scan."
        val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(location)) return "Turn on Location, then tap Scan."
        return null
    }

    fun start() {
        unavailableReason()?.let { stop(); status = it; return }
        if (channel != null) { scan(); return }
        val attempt = ++generation
        val c = manager!!.initialize(context, handler.looper) {
            if (generation == attempt) {
                stop()
                status = "Wi-Fi Direct stopped. Tap Scan to retry."
            }
        } ?: run { status = "Cannot open Wi-Fi Direct."; return }
        channel = c
        chat.start(c)
        status = "Starting nearby discovery…"
        manager.setDnsSdResponseListeners(c, { _, type, _ ->
            if (channel === c && type.startsWith(NearbyAnnouncement.SERVICE_TYPE)) {
                Log.d(LOG_TAG, "NearTalk service discovered")
            }
        }, { domain, record, device ->
            if (channel === c) {
                val peer = Peer(device.deviceAddress, device.deviceName.orEmpty().ifBlank { "Nearby peer" })
                val item = NearbyAnnouncement.decode(domain, record, peer)
                if (item != null) {
                    Log.d(LOG_TAG, "Received valid announcement")
                    receive(item)
                } else if (domain.endsWith(".${NearbyAnnouncement.SERVICE_TYPE}.local.", ignoreCase = true)) {
                    Log.w(LOG_TAG, "Received NearTalk TXT record, but message validation failed")
                }
            }
        })
        manager.addServiceRequest(c, WifiP2pDnsSdServiceRequest.newInstance(), listener(c,
            success = { scan() },
            failure = { stop(); status = "Cannot start discovery: $it. Tap Scan to retry." }
        ))
    }

    private fun scan() {
        handler.removeCallbacks(rescan)
        if (chat.blocksDiscovery) return
        val c = channel ?: return
        unavailableReason()?.let { stop(); status = it; return }
        // Restart discovery so peers can answer with their current announcements.
        manager!!.stopPeerDiscovery(c, listener(c,
            success = { discover(c) },
            failure = { discover(c) }
        ))
    }

    private fun discover(c: WifiP2pManager.Channel) {
        if (chat.blocksDiscovery) return
        manager!!.discoverServices(c, listener(c,
            success = {
                if (!chat.blocksDiscovery) {
                    canSend = !publishing
                    status = "Listening for peer messages"
                    Log.d(LOG_TAG, "Discovery started")
                    handler.postDelayed(rescan, 15_000)
                }
            },
            failure = {
                if (!chat.blocksDiscovery) {
                    canSend = false
                    status = "Discovery: $it. Retrying…"
                    handler.postDelayed(rescan, 15_000)
                }
            }
        ))
    }

    fun send(text: String, onPublished: () -> Unit) {
        val c = channel ?: return
        unavailableReason()?.let { stop(); status = it; return }
        if (!canSend || publishing || chat.blocksDiscovery) return
        val message = text.trim()
        if (message.isBlank()) return
        if (message.toByteArray(Charsets.UTF_8).size > NearbyAnnouncement.MAX_BYTES) {
            status = "Message is too long: maximum 180 UTF-8 bytes (Greek letters usually use 2)."
            return
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val service = WifiP2pDnsSdServiceInfo.newInstance(
            id, NearbyAnnouncement.SERVICE_TYPE, NearbyAnnouncement.encode(id, message)
        )
        publishing = true
        canSend = false
        fun failed(reason: String) {
            publishing = false
            canSend = !chat.blocksDiscovery
            status = "Announcement failed: $reason. Your draft was kept."
        }
        fun publish() {
            manager!!.addLocalService(c, service, listener(c,
                success = {
                    advertisements.addLast(service)
                    publishing = false
                    canSend = !chat.blocksDiscovery
                    receive(BroadcastItem(id, Peer("self", Build.MODEL), message, isOwn = true))
                    status = "Advertised nearby. Delivery is not confirmed."
                    Log.d(LOG_TAG, "Announcement registered locally")
                    onPublished()
                },
                failure = { failed(it) }
            ))
        }
        // Keep only the last three announcements discoverable.
        if (advertisements.size == 3) {
            val oldest = advertisements.first
            manager!!.removeLocalService(c, oldest, listener(c,
                success = { advertisements.removeFirst(); publish() },
                failure = { failed(it) }
            ))
        } else {
            publish()
        }
    }

    fun requestUnicast() {
        val c = channel ?: return
        val reason = unavailableReason()
        if (reason != null) { stop(); status = reason; return }
        if (!canSend || publishing || !chat.canStart) return
        val id = UUID.randomUUID().toString().replace("-", "")
        val text = "I want to talk privately."
        val service = WifiP2pDnsSdServiceInfo.newInstance(id, NearbyAnnouncement.SERVICE_TYPE,
            NearbyAnnouncement.encode(id, text, request = true))
        if (!chat.offer(id)) return
        requestId = id
        requestService = service
        manager!!.addLocalService(c, service, listener(c,
            success = {
                if (requestService === service && chat.phase == ChatPhase.Waiting) {
                    receive(BroadcastItem(id, Peer("self", Build.MODEL), text,
                        kind = BroadcastKind.UnicastRequest, isOwn = true, requestId = id))
                } else {
                    manager.removeLocalService(c, service, null)
                }
            },
            failure = { reason ->
                if (requestService === service) chat.disconnect("Could not advertise request: $reason")
            }
        ))
    }

    fun acceptRequest(item: BroadcastItem) {
        val reason = unavailableReason()
        if (reason != null) { stop(); status = reason; return }
        if (!chat.canStart) return
        chat.accept(item)
        if (chat.phase == ChatPhase.Connecting) {
            messages = messages.map { message ->
                if (message.id == item.id) message.copy(requestOpen = false) else message
            }
        }
    }

    private fun closeOffer() {
        val service = requestService
        val id = requestId
        requestService = null
        requestId = null
        if (id != null) {
            messages = messages.map { message ->
                if (message.isOwn && message.requestId == id) message.copy(requestOpen = false) else message
            }
        }
        val c = channel
        if (service != null && c != null) {
            try { manager!!.removeLocalService(c, service, null) }
            catch (_: SecurityException) { }
        }
    }

    private fun receive(item: BroadcastItem) {
        val key = "${item.sender.id}:${item.id}"
        if (!seen.add(key)) return
        if (seen.size > 4096) seen.remove(seen.first())
        messages = (messages + item.copy(id = key)).takeLast(200)
    }

    private fun listener(
        c: WifiP2pManager.Channel,
        success: () -> Unit,
        failure: (String) -> Unit
    ) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { if (channel === c) success() }
        override fun onFailure(reason: Int) {
            if (channel !== c) return
            val description = when (reason) {
                WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is unsupported"
                WifiP2pManager.NO_SERVICE_REQUESTS -> "no discovery request is registered"
                else -> "Wi-Fi Direct error $reason"
            }
            Log.w(LOG_TAG, description)
            failure(description)
        }
    }

    fun stop() {
        generation++
        chat.stop()
        handler.removeCallbacks(rescan)
        val c = channel
        channel = null
        publishing = false
        canSend = false
        advertisements.clear()
        if (c != null) {
            runCatching {
                manager!!.stopPeerDiscovery(c, null)
                manager.clearLocalServices(c, null)
                manager.clearServiceRequests(c, null)
            }
            if (Build.VERSION.SDK_INT >= 27) c.close()
        }
    }
}
