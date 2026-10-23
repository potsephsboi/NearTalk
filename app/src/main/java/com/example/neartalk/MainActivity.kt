package com.example.neartalk

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.neartalk.ui.theme.NearTalkTheme

class MainActivity : ComponentActivity() {
    private lateinit var nearby: WifiDirectBroadcasts
    private var visible = false
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (visible) nearby.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        nearby = WifiDirectBroadcasts(this)

        setContent {
            var broadcastDraft by rememberSaveable { mutableStateOf("") }
            var unicastDraft by rememberSaveable { mutableStateOf("") }

            NearTalkTheme {
                NearTalkApp(
                    state = NearTalkUiState(
                        broadcasts = nearby.messages,
                        broadcastStatus = nearby.status,
                        canBroadcast = nearby.canSend,
                        connectedPeer = nearby.chat.peer,
                        unicastMessages = nearby.chat.messages,
                        chatPhase = nearby.chat.phase,
                        unicastStatus = nearby.chat.status,
                        canRequestUnicast = nearby.chat.canStart && nearby.canSend,
                        canSendUnicast = nearby.chat.phase == ChatPhase.Connected && !nearby.chat.sending,
                        broadcastDraft = broadcastDraft,
                        unicastDraft = unicastDraft
                    ),
                    onBroadcastDraftChange = { broadcastDraft = it },
                    onUnicastDraftChange = { unicastDraft = it },
                    onSendBroadcast = { text ->
                        val sentDraft = broadcastDraft
                        nearby.send(text) {
                            if (broadcastDraft == sentDraft) broadcastDraft = ""
                        }
                    },
                    onRequestUnicast = { nearby.requestUnicast() },
                    onAcceptRequest = { item -> nearby.acceptRequest(item) },
                    onSendUnicast = { text ->
                        val sentDraft = unicastDraft
                        nearby.chat.send(text) {
                            if (unicastDraft == sentDraft) unicastDraft = ""
                        }
                    },
                    onDisconnect = { nearby.chat.disconnect() },
                    onScan = {
                        if (nearby.hasPermissions()) nearby.start()
                        else permissionRequest.launch(nearby.permissions)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (nearby.hasPermissions()) nearby.start()
    }

    override fun onStop() {
        visible = false
        nearby.stop()
        super.onStop()
    }
}
