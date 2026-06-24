package com.zecmo.internethighfive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    authViewModel: AuthViewModel = viewModel()
) {
    val authState by authViewModel.authState.collectAsState()
    val username = when (authState) {
        is AuthState.LoggedIn -> (authState as AuthState.LoggedIn).username
        else -> "Unknown User"
    }
    
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Profile",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showLogoutConfirmation = true }) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = username,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            Text(
                text = "Connect your accounts",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp)
            )

            OutlinedButton(
                onClick = { /* TODO: Implement Google Auth */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Text("Connect with Google")
                }
            }

            OutlinedButton(
                onClick = { /* TODO: Implement Twitter Auth */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("Connect with Twitter")
                }
            }

            OutlinedButton(
                onClick = { /* TODO: Implement Facebook Auth */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ThumbUp, contentDescription = null)
                    Text("Connect with Facebook")
                }
            }

            // Support section
            Text(
                text = "Support Me",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp)
            )

            OutlinedButton(
                onClick = { /* TODO: Implement Patreon link */ },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null)
                    Text("Support on Patreon")
                }
            }
        }

        if (showLogoutConfirmation) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirmation = false },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(
                                onClick = {
                                    showLogoutConfirmation = false
                                    onLogout()
                                }
                            ) {
                                Text("Logout")
                            }
                            TextButton(
                                onClick = { showLogoutConfirmation = false }
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                },
                title = {
                    Text(
                        "Would you like to logout?",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            )
        }
    }
} 