package com.example.neartalk

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class WifiDirectChat(
    context: Context,
    private val onDiscoveryPaused: (Boolean) -> Unit,
    private val onOfferClosed: () -> Unit
) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var attempt: Attempt? = null
    private var nextMessageId = 0L
    private var available by mutableStateOf(false)
    private var groupPresent by mutableStateOf(false)
    private val connectTimeout = Runnable { disconnect("Connection timed out. Try a new request.") }

    var phase by mutableStateOf(ChatPhase.Idle)
        private set
    var status by mutableStateOf("No established connection with peer.")
        private set
    var peer by mutableStateOf<Peer?>(null)
        private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var sending by mutableStateOf(false)
        private set

    val canStart: Boolean
        get() = available && !groupPresent && phase == ChatPhase.Idle
    val blocksDiscovery: Boolean
        get() = phase != ChatPhase.Idle && phase != ChatPhase.Waiting

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = channel ?: return
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                queryConnection(c)
            }
            if (intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                disconnect("Wi-Fi Direct was turned off.")
            }
        }
    }

    fun start(c: WifiP2pManager.Channel) {
        channel = c
        available = false
        if (!receiverRegistered) {
            val filter = IntentFilter()
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        queryConnection(c)
    }

    private fun queryConnection(c: WifiP2pManager.Channel) {
        try {
            manager!!.requestConnectionInfo(c) { info ->
                if (channel === c) connectionChanged(info)
            }
        } catch (_: SecurityException) {
            disconnect("Wi-Fi permission was revoked.")
        }
    }

    private fun begin(id: String, requester: Boolean): Attempt {
        val current = Attempt(id, requester)
        attempt = current
        peer = null
        messages = emptyList()
        return current
    }

    fun offer(id: String): Boolean {
        if (!canStart) return false
        begin(id, true)
        phase = ChatPhase.Waiting
        status = "Waiting for someone to accept your request."
        return true
    }

    fun accept(item: BroadcastItem) {
        val c = channel ?: return
        val id = item.requestId
        if (!canStart || item.isOwn || !item.requestOpen || id == null) return
        val current = begin(id, false)
        phase = ChatPhase.Connecting
        status = "Connecting to ${item.sender.name}. Accept Android's Wi-Fi invitation if shown."
        onDiscoveryPaused(true)
        handler.postDelayed(connectTimeout, 60_000)
        val config = WifiP2pConfig()
        config.deviceAddress = item.sender.id
        config.wps.setup = WpsInfo.PBC
        // connect() stops discovery itself; stopPeerDiscovery() can forget the peer.
        try {
            manager!!.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { }
                override fun onFailure(reason: Int) {
                    if (attempt === current) disconnect("Wi-Fi connection failed ($reason). Try again.")
                }
            })
        } catch (_: SecurityException) {
            disconnect("Wi-Fi permission was revoked.")
        }
    }

    private fun connectionChanged(info: WifiP2pInfo) {
        groupPresent = info.groupFormed
        available = true
        val current = attempt
        if (current == null) {
            if (groupPresent && phase == ChatPhase.Idle) {
                status = "An existing Wi-Fi Direct group is active. Disconnect it in Wi-Fi settings first."
            }
            return
        }
        if (!info.groupFormed) {
            if (current.started) disconnect("Wi-Fi Direct connection lost.")
            return
        }
        if (current.started) return
        val owner = info.groupOwnerAddress ?: return
        current.started = true
        phase = ChatPhase.Connecting
        status = "Establishing the encrypted connection…"
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, 50_000)
        onDiscoveryPaused(true)
        onOfferClosed()
        Thread({ openSocket(current, info.isGroupOwner, owner) }, "NearTalk-reader").start()
    }

    private fun openSocket(current: Attempt, server: Boolean, owner: InetAddress) {
        try {
            val socket: Socket
            if (server) {
                val listening = ServerSocket()
                synchronized(current) {
                    if (current.cancelled) { listening.close(); return }
                    current.server = listening
                }
                listening.reuseAddress = true
                listening.bind(InetSocketAddress(owner, PORT), 1)
                listening.soTimeout = 25_000
                socket = listening.accept()
                listening.close()
            } else {
                socket = connectSocket(current, owner)
            }
            synchronized(current) {
                if (current.cancelled) { socket.close(); return }
                current.socket = socket
                current.session = SecureSession(socket, server, current.requester,
                    current.id, Build.MODEL, sessionListener(current))
            }
            current.session!!.run()
        } catch (e: Exception) {
            handler.post {
                if (attempt === current) disconnect("Cannot open chat: ${e.message ?: "I/O error"}")
            }
        }
    }

    private fun connectSocket(current: Attempt, owner: InetAddress): Socket {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val socket = Socket()
            synchronized(current) {
                if (current.cancelled) { socket.close(); throw IOException("Cancelled") }
                current.socket = socket
            }
            try {
                socket.connect(InetSocketAddress(owner, PORT), 2000)
                return socket
            } catch (e: IOException) {
                socket.close()
                if (current.cancelled) throw e
                Thread.sleep(300)
            }
        }
        throw IOException("Peer did not open its chat socket")
    }

    private fun sessionListener(current: Attempt): SecureSession.Listener {
        return object : SecureSession.Listener {
            override fun onReady(peerName: String) {
                handler.post {
                    if (attempt === current) {
                        handler.removeCallbacks(connectTimeout)
                        peer = Peer(current.id, peerName)
                        phase = ChatPhase.Connected
                        status = "Encrypted connection with $peerName"
                    }
                }
            }
            override fun onMessage(text: String) {
                handler.post {
                    if (attempt === current) addMessage(text, false)
                }
            }
            override fun onClosed(reason: String) {
                handler.post {
                    if (attempt === current) disconnect(reason)
                }
            }
        }
    }

    fun send(text: String, onSent: () -> Unit) {
        val current = attempt ?: return
        if (phase != ChatPhase.Connected || sending) return
        val message = text.trim()
        if (message.isBlank()) return
        if (message.toByteArray(Charsets.UTF_8).size > SecureSession.MAX_MESSAGE_BYTES) {
            status = "Message is too long: maximum 4096 UTF-8 bytes."
            return
        }
        sending = true
        current.writer.execute {
            try {
                current.session!!.sendMessage(message)
                handler.post {
                    if (attempt === current) {
                        sending = false
                        addMessage(message, true)
                        onSent()
                    }
                }
            } catch (_: Exception) {
                handler.post { if (attempt === current) disconnect("Send failed. Your draft was kept.") }
            }
        }
    }

    private fun addMessage(text: String, own: Boolean) {
        nextMessageId++
        messages = (messages + ChatMessage(nextMessageId.toString(), text, own)).takeLast(200)
    }

    fun disconnect(reason: String = "Disconnected.") {
        val current = attempt
        attempt = null
        handler.removeCallbacks(connectTimeout)
        current?.close()
        onOfferClosed()
        peer = null
        sending = false
        status = reason
        val c = channel
        if (current == null || c == null) return
        phase = ChatPhase.Closing
        onDiscoveryPaused(true)
        val finished = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (channel !== c) return
                groupPresent = false
                phase = ChatPhase.Idle
                onDiscoveryPaused(false)
            }
            override fun onFailure(reason: Int) {
                if (channel !== c) return
                phase = ChatPhase.Idle
                queryConnection(c)
                onDiscoveryPaused(false)
            }
        }
        try {
            manager!!.cancelConnect(c, null)
            manager.removeGroup(c, finished)
        } catch (_: SecurityException) {
            available = false
            phase = ChatPhase.Idle
        }
    }

    fun stop() {
        disconnect("No established connection with peer.")
        channel = null
        available = false
        phase = ChatPhase.Idle
        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    private class Attempt(val id: String, val requester: Boolean) {
        var started = false
        @Volatile var cancelled = false
        var socket: Socket? = null
        var server: ServerSocket? = null
        var session: SecureSession? = null
        val writer = Executors.newSingleThreadExecutor()

        @Synchronized fun close() {
            cancelled = true
            session?.close()
            try { socket?.close() } catch (_: IOException) { }
            try { server?.close() } catch (_: IOException) { }
            writer.shutdownNow()
        }
    }

    companion object {
        private const val PORT = 43827
    }
}
