package com.zecmo.internethighfive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zecmo.internethighfive.data.User

private val NOTIFICATION_PREFS = listOf(
    "all" to "All",
    "targeted" to "Direct",
    "none" to "None"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendDetailSheet(
    friend: User,
    friendsViewModel: FriendsViewModel,
    onDismiss: () -> Unit,
    statsViewModel: HighFiveViewModel = viewModel()
) {
    val partnerStats by statsViewModel.partnerStats.collectAsState()
    var pref by remember(friend.id) { mutableStateOf("all") }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(friend.id) {
        statsViewModel.loadStatsForFriend(friend.id)
        pref = friendsViewModel.fetchNotificationPref(friend.id)
    }
    DisposableEffect(Unit) {
        onDispose { statsViewModel.clearPartnerStats() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = if (friend.isOnline) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(12.dp)
                ) {}
                Text(friend.username, style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                if (friend.isOnline) "Online" else "Offline",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Stats
            Text("Hi-5 Stats", style = MaterialTheme.typography.titleMedium)
            if (partnerStats == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                val stats = partnerStats!!
                if (stats.totalCount == 0) {
                    Text("No high fives yet — go say hi!", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                } else {
                    Text("${stats.flavorEmoji} ${stats.flavorLabel}", style = MaterialTheme.typography.titleMedium)
                    Text("${stats.totalCount} total high fives", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Perfect!" to "🌟", "Great!" to "⭐", "Good" to "👍", "Ok" to "👋", "Meh" to "🤷")
                            .forEach { (key, emoji) ->
                                val count = stats.qualityBreakdown[key] ?: 0
                                if (count > 0) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            "$emoji $count",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                    }
                }
            }

            HorizontalDivider()

            // Notification preference
            Text("Notifications from ${friend.username}", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                NOTIFICATION_PREFS.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = pref == value,
                        onClick = {
                            pref = value
                            friendsViewModel.setNotificationPref(friend.id, value)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = NOTIFICATION_PREFS.size)
                    ) {
                        Text(label)
                    }
                }
            }
            Text(
                when (pref) {
                    "all" -> "You'll get all notifications from ${friend.username}."
                    "targeted" -> "You'll only get direct invite from ${friend.username}."
                    else -> "You won't get notifications from ${friend.username}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Pushes Remove Friend further down so it lands behind the nav bar / gesture
            // line at the sheet's default half-expanded position — user has to drag
            // further to reveal it, which is intentional.
            Spacer(Modifier.height(48.dp))

            HorizontalDivider()

            OutlinedButton(
                onClick = { showRemoveConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Remove Friend")
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove ${friend.username}?") },
            text = { Text("You'll no longer be friends and won't see each other in your lobby.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    friendsViewModel.removeFriend(friend.id)
                    onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
