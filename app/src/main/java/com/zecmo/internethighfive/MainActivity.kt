package com.zecmo.internethighfive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zecmo.internethighfive.navigation.Screen
import com.zecmo.internethighfive.ui.*
import com.zecmo.internethighfive.ui.theme.InternetHighFiveTheme
import com.google.firebase.database.FirebaseDatabase
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Firebase debug logging
        FirebaseDatabase.getInstance().setLogLevel(com.google.firebase.database.Logger.Level.DEBUG)
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show an explanation to the user
                    Log.d(TAG, "Should show notification permission rationale")
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            InternetHighFiveTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel()
                val authState by authViewModel.authState.collectAsState()

                NavHost(
                    navController = navController,
                    startDestination = Screen.Login.route
                ) {
                    composable(Screen.Login.route) {
                        LoginScreen(
                            onLoginSuccess = {
                                navController.navigate(Screen.Lobby.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        )
                    }
                    
                    composable(Screen.Lobby.route) {
                        LobbyScreen(
                            onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                            onNavigateToFriends = { navController.navigate(Screen.Friends.route) },
                            onNavigateToHighFive = { userId ->
                                navController.navigate("${Screen.HighFive.route}/$userId")
                            }
                        )
                    }
                    
                    composable(
                        route = "${Screen.HighFive.route}/{userId}",
                        arguments = listOf(navArgument("userId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val userId = backStackEntry.arguments?.getString("userId") ?: return@composable
                        HighFiveScreen(
                            partnerId = userId,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                    
                    composable(route = Screen.Friends.route) {
                        FriendsScreen(
                            onNavigateBack = { navController.navigateUp() },
                            onNavigateToAddUser = { navController.navigate(Screen.AddUser.route) },
                            onNavigateToHighFive = { userId -> 
                                navController.navigate("${Screen.HighFive.route}/$userId")
                            }
                        )
                    }
                    
                    composable(Screen.Profile.route) {
                        ProfileScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onLogout = {
                                authViewModel.logout()
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                    
                    composable(Screen.AddUser.route) {
                        AddUserScreen(
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }

                // Handle auth state changes
                LaunchedEffect(authState) {
                    when (authState) {
                        is AuthState.LoggedOut -> {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                        is AuthState.LoggedIn -> {
                            if (navController.currentBackStackEntry?.destination?.route == Screen.Login.route) {
                                navController.navigate(Screen.Lobby.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        }
                        else -> {} // Handle other states if needed
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighFiveScreen(
    partnerId: String,
    onNavigateBack: () -> Unit,
    viewModel: HighFiveViewModel = viewModel()
) {
    val highFiveState by viewModel.highFiveState.collectAsState()
    val connectedUser by viewModel.connectedUser.collectAsState()
    val isReady by viewModel.isReady.collectAsState()
    val notification by viewModel.inAppNotification.collectAsState()
    
    LaunchedEffect(partnerId) {
        viewModel.connectToUser(partnerId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("High Five with ${connectedUser?.username ?: "..."}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = {
            notification?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissNotification() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(message)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Partner Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (connectedUser != null) {
                        Text(
                            text = connectedUser!!.username,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = CircleShape,
                                color = if (viewModel.isPartnerReady()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.surfaceVariant
                            ) {}
                            Text(
                                text = if (viewModel.isPartnerReady()) "Ready!" else "Not Ready",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (viewModel.isPartnerReady()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            // Ready Button
            Button(
                onClick = { viewModel.setReady(!isReady) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isReady) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isReady) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null
                    )
                    Text(if (isReady) "I'm Ready!" else "Get Ready")
                }
            }

            // High Five Area
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (isReady && viewModel.isPartnerReady()) {
                                viewModel.initiateHighFive()
                            }
                        }
                    },
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(
                    width = 2.dp,
                    color = when (highFiveState) {
                        is HighFiveState.Success -> MaterialTheme.colorScheme.primary
                        is HighFiveState.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (highFiveState) {
                        is HighFiveState.Success -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "High Five!",
                                    style = MaterialTheme.typography.displayMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Quality: ${(highFiveState as HighFiveState.Success).quality * 100}%",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                        is HighFiveState.Error -> {
                            Text(
                                text = (highFiveState as HighFiveState.Error).message,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        is HighFiveState.Waiting -> {
                            if (isReady && viewModel.isPartnerReady()) {
                                Text(
                                    text = "Tap to High Five!",
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            } else {
                                Text(
                                    text = "Waiting for both users to be ready...",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        HighFiveState.Idle -> {
                            Text(
                                text = if (isReady && viewModel.isPartnerReady())
                                    "Both ready! Tap to High Five!"
                                else if (isReady)
                                    "Waiting for partner..."
                                else
                                    "Get ready to High Five!",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getQualityMessage(quality: Float): String {
    return when {
        quality >= 0.9f -> "Perfect! 🌟"
        quality >= 0.7f -> "Great! ⭐"
        quality >= 0.5f -> "Good! 👍"
        else -> "Nice try! 👋"
    }
}