package com.zecmo.internethighfive.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToFriends: () -> Unit,
    onNavigateToHighFive: (String) -> Unit,
    viewModel: FriendsViewModel = viewModel()
) {
    val friends by viewModel.friends.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val handRaised = currentUser?.handRaised == true
    val inSession = currentUser?.isInSession == true
    var messageText by remember { mutableStateOf("") }
    var navigating by remember { mutableStateOf(false) }
    val placeholders = remember {
        listOf("What's the occasion?", "What for?", "What are we celebrating?", "Why Hi?")
    }
    val placeholder = remember { placeholders.random() }
    var selectedFriend by remember { mutableStateOf<User?>(null) }

    Scaffold(
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    enabled = !handRaised
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "internet Hi-5",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.Person, contentDescription = "Profile")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToFriends) {
                        Icon(Icons.Default.Search, contentDescription = "Search Friends")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            error?.let { errorMsg ->
                Text(
                    text = errorMsg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Section
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.hi5_logo),
                                contentDescription = "Raise Hand",
                                modifier = Modifier
                                    .size(150.dp)
                                    .padding(bottom = 16.dp)
                                    .alpha(if (handRaised) 1f else 0.5f)
                                    .clickable {
                                        if (!handRaised && !navigating) {
                                            navigating = true
                                            viewModel.updateHandRaisedStatus(true, messageText)
                                            onNavigateToHighFive("open:$messageText")
                                        } else if (handRaised) {
                                            viewModel.updateHandRaisedStatus(false)
                                        }
                                    },
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = when {
                                    inSession -> "High Fiving! 🙌"
                                    handRaised -> "Hand raised! 🙋"
                                    else -> "Tap to raise your hand"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                if (friends.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No friends yet!",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Add some friends to start high fiving!",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Button(
                                onClick = onNavigateToFriends,
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                Text("Find Friends")
                            }
                        }
                    }
                } else {
                    items(
                        items = friends,
                        key = { it.id }
                    ) { friend ->
                        FriendCard(
                            user = friend,
                            onClick = { selectedFriend = friend },
                            onHighFiveRequest = {
                                if (!navigating) {
                                    navigating = true
                                    if (friend.hasActiveHighFive) {
                                        onNavigateToHighFive(friend.id)
                                    } else {
                                        viewModel.inviteFriend(friend.id)
                                        onNavigateToHighFive("invite:${friend.id}:${friend.username}:$messageText")
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    selectedFriend?.let { friend ->
        FriendDetailSheet(
            friend = friend,
            friendsViewModel = viewModel,
            onDismiss = { selectedFriend = null }
        )
    }
}

@Composable
private fun FriendCard(
    user: User,
    onClick: () -> Unit,
    onHighFiveRequest: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (user.isOnline)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Online status indicator
            Surface(
                color = if (user.isOnline) MaterialTheme.colorScheme.primary 
                       else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (user.isOnline) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.size(16.dp)
            ) {}
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (user.isOnline) "Active now" else "Last active: ${formatTimestamp(user.lastLoginAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (user.isOnline) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onHighFiveRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        user.isInSession -> Color(0xFFFFA000)       // Amber
                        user.hasActiveHighFive -> Color(0xFF4CAF50) // Green
                        else -> MaterialTheme.colorScheme.primary
                    }
                ),
                enabled = !user.isInSession
            ) {
                Text(when {
                    user.isInSession -> "High Fiving! 🙌"
                    user.hasActiveHighFive -> "Hand Raised! ✋"
                    else -> "High Five!"
                })
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 1000 * 60 -> "Just now"
        diff < 1000 * 60 * 60 -> "${diff / (1000 * 60)} minutes ago"
        diff < 1000 * 60 * 60 * 24 -> "${diff / (1000 * 60 * 60)} hours ago"
        else -> "${diff / (1000 * 60 * 60 * 24)} days ago"
    }
} 