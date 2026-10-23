package com.example.neartalk

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.neartalk.ui.theme.NearTalkTheme

private val previewPeer = Peer("peer-alex", "Alex")

@Preview(name = "Broadcast feed", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun BroadcastPreview() {
    PreviewApp(
        state = NearTalkUiState(
            broadcasts = listOf(
                BroadcastItem("1", previewPeer, "Hello, is anyone nearby?"),
                BroadcastItem("2", Peer("local", "You"), "Hi!", isOwn = true),
                BroadcastItem(
                    "3", previewPeer, "I'd like to talk one-to-one.",
                    kind = BroadcastKind.UnicastRequest
                )
            )
        )
    )
}

@Preview(name = "Unicast disconnected", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun DisconnectedPreview() {
    PreviewApp(NearTalkUiState(), ChatTab.Unicast)
}

@Preview(name = "Unicast connected", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ConnectedPreview() {
    PreviewApp(
        state = NearTalkUiState(
            connectedPeer = previewPeer,
            chatPhase = ChatPhase.Connected,
            unicastStatus = "Encrypted connection with Alex.",
            canSendUnicast = true,
            unicastMessages = listOf(
                ChatMessage("1", "Hi, thanks for accepting.", isOwn = false),
                ChatMessage("2", "Hello!", isOwn = true)
            )
        ),
        tab = ChatTab.Unicast
    )
}

@Composable
private fun PreviewApp(state: NearTalkUiState, tab: ChatTab = ChatTab.Broadcast) {
    NearTalkTheme {
        NearTalkApp(
            state = state,
            onBroadcastDraftChange = {},
            onUnicastDraftChange = {},
            onSendBroadcast = {},
            onRequestUnicast = {},
            onAcceptRequest = {},
            onSendUnicast = {},
            initialTab = tab
        )
    }
}
