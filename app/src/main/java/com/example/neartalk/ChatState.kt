package com.example.neartalk

data class Peer(val id: String, val name: String)

enum class BroadcastKind { Message, UnicastRequest }

enum class ChatPhase { Idle, Waiting, Connecting, Connected, Closing }

data class BroadcastItem(
    val id: String,
    val sender: Peer,
    val text: String,
    val kind: BroadcastKind = BroadcastKind.Message,
    val isOwn: Boolean = false,
    val requestOpen: Boolean = true,
    val requestId: String? = null
)

data class ChatMessage(
    val id: String,
    val text: String,
    val isOwn: Boolean
)

data class NearTalkUiState(
    val broadcasts: List<BroadcastItem> = emptyList(),
    val connectedPeer: Peer? = null,
    val unicastMessages: List<ChatMessage> = emptyList(),
    val broadcastDraft: String = "",
    val unicastDraft: String = "",
    val broadcastStatus: String = "Nearby announcements",
    val canBroadcast: Boolean = false,
    val chatPhase: ChatPhase = ChatPhase.Idle,
    val unicastStatus: String = "No established connection with peer.",
    val canRequestUnicast: Boolean = false,
    val canSendUnicast: Boolean = false
)
