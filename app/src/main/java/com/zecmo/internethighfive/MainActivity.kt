package com.zecmo.internethighfive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zecmo.internethighfive.navigation.Screen
import com.zecmo.internethighfive.ui.*
import com.zecmo.internethighfive.ui.theme.InternetHighFiveTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SENDER_ID = "sender_id"
    }

    // Holds a sender ID from a notification tap — read by the Compose nav graph
    private val pendingSenderId = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Notification tap when app was already running in background
        intent.getStringExtra(EXTRA_SENDER_ID)?.let { pendingSenderId.value = it }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) Log.d(TAG, "Notification permission granted")
        else Log.w(TAG, "Notification permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Notification tap when app was closed
        pendingSenderId.value = intent.getStringExtra(EXTRA_SENDER_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {}
                else -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge()
        setContent {
            InternetHighFiveTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel()
                val authState by authViewModel.authState.collectAsState()

                NavHost(navController = navController, startDestination = Screen.Login.route) {
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
                    composable(Screen.Friends.route) {
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
                        AddUserScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }

                // Single effect handles auth transitions AND notification deep links
                LaunchedEffect(authState) {
                    when (authState) {
                        is AuthState.LoggedOut -> navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                        is AuthState.LoggedIn -> {
                            // Ensure Lobby is the base destination
                            if (navController.currentBackStackEntry?.destination?.route == Screen.Login.route) {
                                navController.navigate(Screen.Lobby.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                            // If opened from a notification, go straight to high five
                            pendingSenderId.value?.let { sender ->
                                navController.navigate("${Screen.HighFive.route}/$sender") {
                                    popUpTo(Screen.Lobby.route) { inclusive = false }
                                }
                                pendingSenderId.value = null
                            }
                        }
                        else -> {}
                    }
                }

                // Handle notification tap when app was already running in background
                val senderId = pendingSenderId.value
                LaunchedEffect(senderId) {
                    if (senderId != null && authState is AuthState.LoggedIn) {
                        navController.navigate("${Screen.HighFive.route}/$senderId") {
                            popUpTo(Screen.Lobby.route) { inclusive = false }
                        }
                        pendingSenderId.value = null
                    }
                }
            }
        }
    }
}
