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
import androidx.navigation.compose.currentBackStackEntryAsState
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
            // Forced dark: the app now paints its own navy->gray gradient background on
            // every screen regardless of system theme, so text colors must stay light too.
            InternetHighFiveTheme(darkTheme = true, dynamicColor = false) {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel()
                val friendsViewModel: FriendsViewModel = viewModel()
                val authState by authViewModel.authState.collectAsState()
                // Observed as state so the deep-link effect re-runs when the back stack
                // actually settles — this is what prevents the navigate-Lobby-then-HighFive
                // race that left the partner stranded on the lobby.
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route

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
                            },
                            onNavigateToGradientDebug = { navController.navigate(Screen.GradientDebug.route) },
                            viewModel = friendsViewModel
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
                            },
                            viewModel = friendsViewModel
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
                            onNavigateBack = { navController.popBackStack() },
                            viewModel = friendsViewModel
                        )
                    }
                    composable(Screen.GradientDebug.route) {
                        GradientDebugScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }

                // Effect 1 — base routing. Only moves between Login and the logged-in
                // base (Lobby). Deliberately does NOT touch the deep link.
                LaunchedEffect(authState) {
                    when (authState) {
                        is AuthState.LoggedOut -> navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                        is AuthState.LoggedIn -> {
                            if (currentRoute == null || currentRoute == Screen.Login.route) {
                                navController.navigate(Screen.Lobby.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        }
                        else -> {}
                    }
                }

                // Effect 2 — notification deep link. The activity is recreated on tap
                // (FLAG_ACTIVITY_CLEAR_TASK) with pendingSenderId set in onCreate. We
                // wait until logged in AND the back stack has actually settled past
                // Login before navigating, so the HighFive destination can never be
                // dropped by a popUpTo(Lobby) that ran before Lobby existed.
                LaunchedEffect(authState, pendingSenderId.value, currentRoute) {
                    val sender = pendingSenderId.value ?: return@LaunchedEffect
                    if (authState !is AuthState.LoggedIn) return@LaunchedEffect
                    if (currentRoute == null || currentRoute == Screen.Login.route) return@LaunchedEffect

                    val highFiveRoute = "${Screen.HighFive.route}/{userId}"
                    val alreadyThere = currentRoute == highFiveRoute &&
                        currentBackStackEntry?.arguments?.getString("userId") == sender
                    if (!alreadyThere) {
                        navController.navigate("${Screen.HighFive.route}/$sender") {
                            popUpTo(Screen.Lobby.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    pendingSenderId.value = null
                }
            }
        }
    }
}
