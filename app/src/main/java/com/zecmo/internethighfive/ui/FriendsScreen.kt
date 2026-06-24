package com.zecmo.internethighfive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.zecmo.internethighfive.data.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddUser: () -> Unit,
    onNavigateToHighFive: (String) -> Unit,
    viewModel: FriendsViewModel = viewModel()
) {
    val allUsers by viewModel.allUsers.collectAsState()
    val friendIds by viewModel.friendIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    // Non-friends only (self already filtered in VM)
    val nonFriends = allUsers
        .filter { it.id !in friendIds }
        .filter { searchQuery.isBlank() || it.username.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Find Fivers") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (nonFriends.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val (title, subtitle) = if (searchQuery.isNotBlank()) {
                            "No matches" to "No one found matching \"$searchQuery\""
                        } else {
                            "No new fivers found" to "Everyone you know is already a friend!"
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(nonFriends, key = { it.id }) { user ->
                            FindFiversCard(
                                user = user,
                                onAddFriend = { viewModel.addFriend(user.id) },
                                onHighFive = { onNavigateToHighFive(user.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindFiversCard(
    user: User,
    onAddFriend: () -> Unit,
    onHighFive: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Online dot
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = if (user.isOnline) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {}

            Column(modifier = Modifier.weight(1f)) {
                Text(user.username, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (user.isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // If hand raised, show Join button too
            if (user.hasActiveHighFive) {
                OutlinedButton(onClick = onHighFive) { Text("Join! 🙋") }
                Spacer(Modifier.width(4.dp))
            }

            Button(onClick = onAddFriend) { Text("Add Friend") }
        }
    }
}
