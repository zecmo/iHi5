package com.zecmo.internethighfive.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import com.zecmo.internethighfive.BuildConfig
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.R
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LobbyScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToFriends: () -> Unit,
    onNavigateToHighFive: (String) -> Unit,
    onNavigateToGradientDebug: () -> Unit = {},
    onNavigateToSlapTest: () -> Unit = {},
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
    val sortMode by viewModel.sortMode.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sortReversed by viewModel.sortReversed.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
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
                    // Debug builds only: long-press the title to reach the slap tuner.
                    Text(
                        text = "internet Hi-5",
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (BuildConfig.DEBUG) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = onNavigateToSlapTest
                                    )
                                } else Modifier
                            ),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    // Long-press is a hidden entry point to the gradient debug tuner.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = onNavigateToProfile,
                                onLongClick = onNavigateToGradientDebug
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile")
                    }
                },
                actions = {
                    // Sort lives next to search: both are "how do I find someone" tools,
                    // and the bar is the only always-visible surface above the list.
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort friends")
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false }
                        ) {
                            SortMode.entries.forEach { mode ->
                                val selected = mode == sortMode
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        if (selected) {
                                            // Re-picking the active mode flips its
                                            // direction; the menu stays open so the arrow
                                            // visibly turns over and can be tapped again.
                                            viewModel.toggleSortDirection()
                                        } else {
                                            viewModel.setSortMode(mode)
                                            sortMenuOpen = false
                                        }
                                    },
                                    leadingIcon = {
                                        if (selected) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        } else {
                                            Spacer(Modifier.size(24.dp))
                                        }
                                    },
                                    // Arrow only on the active row — it is that row's
                                    // toggle, so showing it on an inactive one implies a
                                    // control that isn't there. Down = the mode's natural
                                    // order (newest first, A first); up = flipped.
                                    trailingIcon = if (selected) {
                                        {
                                            Icon(
                                                if (sortReversed) Icons.Default.ArrowUpward
                                                else Icons.Default.ArrowDownward,
                                                contentDescription =
                                                    if (sortReversed) "Reversed — tap to restore"
                                                    else "Tap again to reverse"
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }
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
                            .padding(bottom = 16.dp)
                            .clickable {
                                if (!handRaised && !navigating) {
                                    navigating = true
                                    viewModel.updateHandRaisedStatus(true, messageText)
                                    onNavigateToHighFive("open:$messageText")
                                } else if (handRaised) {
                                    viewModel.updateHandRaisedStatus(false)
                                }
                            },
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
                                    .padding(bottom = 16.dp),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = when {
                                    inSession -> "High Fiving! 🙌"
                                    else -> "Raise your hand"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Only claim they have no friends when the load actually succeeded.
                // A failed fetch also yields an empty list, and telling someone with 20
                // friends "No friends yet!" reads as data loss rather than a hiccup.
                if (friends.isEmpty() && error != null) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Couldn't load your friends",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Check your connection and try again.",
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Button(
                                onClick = { viewModel.retryLoad() },
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                } else if (friends.isEmpty() && !isLoading) {
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
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Add some friends to start high fiving!",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
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
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = if (user.isOnline) "Active now" else "Last active: ${formatTimestamp(user.lastLoginAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
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