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
import androidx.compose.ui.draw.alpha
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
    val highFiveSession by viewModel.highFiveSession.collectAsState()
    val touchCount by viewModel.touchCount.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    
    Log.d("HighFiveScreen", "HighFiveScreen composable called with partnerId: $partnerId")
    
    LaunchedEffect(partnerId) {
        Log.d("HighFiveScreen", "LaunchedEffect triggered for partnerId: $partnerId")
        // Check if we're the initiator (user A) or partner (user B)
        if (currentUser?.id == partnerId) {
            // We're the initiator, create a new session
            viewModel.createHighFiveSession(partnerId)
        } else {
            // We're the partner, try to join the existing session
            viewModel.connectToUser(partnerId)
        }
        viewModel.onEnterHighFiveScreen()
    }

    DisposableEffect(Unit) {
        Log.d("HighFiveScreen", "DisposableEffect setup")
        onDispose {
            Log.d("HighFiveScreen", "DisposableEffect onDispose called")
            viewModel.onExitHighFiveScreen()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (highFiveSession?.partnerUsername?.isNotEmpty() == true)
                            "Connected with ${highFiveSession?.partnerUsername}"
                        else
                            "Awaiting Partner"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Touch counter banner
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "Touches: $touchCount",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Main touchable area with hand outline
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            viewModel.incrementTouchCount()
                            viewModel.initiateHighFive()
                        }
                    },
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Hand outline
                    Icon(
                        imageVector = Icons.Default.BackHand,
                        contentDescription = "High Five Hand",
                        modifier = Modifier
                            .size(200.dp)
                            .alpha(0.1f),
                        tint = MaterialTheme.colorScheme.primary
                    )
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