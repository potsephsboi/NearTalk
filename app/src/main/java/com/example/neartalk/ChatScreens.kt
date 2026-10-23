package com.example.neartalk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun BroadcastScreen(
    state: NearTalkUiState,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onRequestUnicast: () -> Unit,
    onAcceptRequest: (BroadcastItem) -> Unit,
    onScan: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.broadcasts.lastOrNull()?.id) {
        if (state.broadcasts.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.broadcastStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onScan) { Text("Scan") }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.broadcasts.isEmpty()) {
                EmptyMessage("No messages yet.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)
                ) {
                    items(state.broadcasts.asReversed(), key = { it.id }) { item ->
                        BroadcastCard(item, state.canRequestUnicast, onAcceptRequest)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onRequestUnicast,
            shape = RoundedCornerShape(4.dp),
            enabled = state.canRequestUnicast,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        ) {
            Text("Request 1–1 communication")
        }
        MessageInput(state.broadcastDraft, onDraftChange, onSend, state.canBroadcast)
    }
}

@Composable
private fun BroadcastCard(
    item: BroadcastItem,
    canConnect: Boolean,
    onAcceptRequest: (BroadcastItem) -> Unit
) {
    val isRequest = item.kind == BroadcastKind.UnicastRequest
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (isRequest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (item.isOwn) "You" else item.sender.name,
                style = MaterialTheme.typography.labelMedium
            )
            if (isRequest) {
                Text(
                    text = "1–1 REQUEST",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(item.text, style = MaterialTheme.typography.bodyMedium)
            if (isRequest) {
                when {
                    !item.requestOpen -> Text("Request closed", style = MaterialTheme.typography.bodySmall)
                    item.isOwn -> Text("Waiting for a peer", style = MaterialTheme.typography.bodySmall)
                    else -> OutlinedButton(
                        onClick = { onAcceptRequest(item) },
                        enabled = canConnect
                    ) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
fun UnicastScreen(
    state: NearTalkUiState,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onDisconnect: () -> Unit = {}
) {
    val peer = state.connectedPeer
    if (state.chatPhase != ChatPhase.Connected || peer == null) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(state.unicastStatus)
            if (state.chatPhase != ChatPhase.Idle && state.chatPhase != ChatPhase.Closing) {
                OutlinedButton(onClick = onDisconnect) { Text("Cancel / Disconnect") }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.unicastMessages.lastOrNull()?.id) {
        if (state.unicastMessages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(state.unicastStatus, modifier = Modifier.weight(1f))
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.unicastMessages.isEmpty()) {
                EmptyMessage("No messages yet.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
                ) {
                    items(state.unicastMessages.asReversed(), key = { it.id }) { message ->
                        ChatBubble(message, peer.name)
                    }
                }
            }
        }
        MessageInput(state.unicastDraft, onDraftChange, onSend, state.canSendUnicast)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, peerName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOwn) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = MaterialTheme.shapes.small,
            color = if (message.isOwn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (message.isOwn) "You" else peerName,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(message.text)
            }
        }
    }
}

@Composable
private fun MessageInput(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("Message") },
            modifier = Modifier.weight(1f),
            maxLines = 4
        )
        Button(
            onClick = { onSend(draft.trim()) },
            shape = RoundedCornerShape(4.dp),
            enabled = enabled && draft.isNotBlank()
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, textAlign = TextAlign.Center)
    }
}
