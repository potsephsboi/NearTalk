package com.example.neartalk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class ChatTab { Broadcast, Unicast }

@Composable
fun     NearTalkApp(
    state: NearTalkUiState,
    onBroadcastDraftChange: (String) -> Unit,
    onUnicastDraftChange: (String) -> Unit,
    onSendBroadcast: (String) -> Unit,
    onRequestUnicast: () -> Unit,
    onAcceptRequest: (BroadcastItem) -> Unit,
    onSendUnicast: (String) -> Unit,
    initialTab: ChatTab = ChatTab.Broadcast,
    onScan: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    LaunchedEffect(state.chatPhase) {
        if (state.chatPhase != ChatPhase.Idle && state.chatPhase != ChatPhase.Closing) {
            selectedTab = ChatTab.Unicast
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                text = selectedTab.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    ChatTab.Broadcast -> BroadcastScreen(
                        state = state,
                        onDraftChange = onBroadcastDraftChange,
                        onSend = onSendBroadcast,
                        onRequestUnicast = onRequestUnicast,
                        onAcceptRequest = onAcceptRequest,
                        onScan = onScan
                    )
                    ChatTab.Unicast -> UnicastScreen(
                        state = state,
                        onDraftChange = onUnicastDraftChange,
                        onSend = onSendUnicast,
                        onDisconnect = onDisconnect
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (tab in ChatTab.values()) {
                    val isSelected = tab == selectedTab
                    OutlinedButton(
                        onClick = { selectedTab = tab },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.weight(1f).semantics { selected = isSelected },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(tab.name)
                    }
                }
            }
        }
    }
}
