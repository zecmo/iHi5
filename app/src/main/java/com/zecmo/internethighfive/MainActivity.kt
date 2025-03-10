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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
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
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.*
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs
import kotlin.math.sqrt
import android.media.AudioManager
import android.media.ToneGenerator

private const val PointerEventTimeoutMillis = 100L  // Timeout for pointer events

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
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    
    // Sound effect setup with error handling
    val toneGen = remember {
        try {
            ToneGenerator(AudioManager.STREAM_SYSTEM, 100)
        } catch (e: Exception) {
            Log.e("HighFiveScreen", "Error creating ToneGenerator", e)
            null
        }
    }
    
    // Sensor states
    var accelerometerForce by remember { mutableStateOf(0f) }
    var maxForce by remember { mutableStateOf(0f) }
    var currentForce by remember { mutableStateOf(0f) }
    var peakForce by remember { mutableStateOf(0f) }  // Track recent peak force
    var lastMessageTime by remember { mutableStateOf(0L) }  // Track when messages were last updated
    
    // Animation states
    var scaleEffect by remember { mutableStateOf(1f) }
    
    // Constants for force calculation
    val baselineGravity = 9.8f  // Earth's gravitational acceleration
    
    // Function to trigger vibration and sound based on force
    fun vibrateOnImpact(force: Float) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Enhanced vibration pattern based on force
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = (force.coerceIn(0f, 30f) * 10).toInt() // Increased amplitude
                val duration = (50 + (force * 2)).toLong().coerceIn(50, 200) // Dynamic duration
                try {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255)))
                } catch (e: SecurityException) {
                    Log.e("HighFiveScreen", "Vibration permission denied", e)
                }
                
                // Play sound with different tones based on force
                try {
                    when {
                        force > 25f -> toneGen?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 150)
                        force > 20f -> toneGen?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 100)
                        else -> toneGen?.startTone(ToneGenerator.TONE_CDMA_PIP, 50)
                    }
                } catch (e: Exception) {
                    Log.e("HighFiveScreen", "Error playing tone", e)
                }
            } else {
                @Suppress("DEPRECATION")
                try {
                    vibrator.vibrate(100)
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                } catch (e: Exception) {
                    Log.e("HighFiveScreen", "Error with vibration or tone", e)
                }
            }
        } catch (e: Exception) {
            Log.e("HighFiveScreen", "Error with vibration service", e)
        }
    }
    
    // Remember sensor manager
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // Combined sensor listener
    val sensorListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val totalForce = sqrt(x * x + y * y + z * z)
                        // Subtract baseline gravity and ensure non-negative
                        val adjustedForce = (totalForce - baselineGravity).coerceAtLeast(0f)
                        
                        // Update force values
                        currentForce = adjustedForce
                        if (adjustedForce > maxForce) maxForce = adjustedForce
                        if (adjustedForce > peakForce) {
                            peakForce = adjustedForce
                            lastMessageTime = System.currentTimeMillis()
                        }
                        accelerometerForce = adjustedForce
                        
                        // Enhanced haptic feedback
                        if (adjustedForce > 3f) {  // Only trigger feedback above threshold
                            when {
                                adjustedForce > 15f -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                adjustedForce > 8f -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                else -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            
                            // Update scale effect based on force
                            scaleEffect = (1f + (adjustedForce / 20f)).coerceIn(1f, 1.5f)
                        }
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // Register accelerometer sensor only
    DisposableEffect(Unit) {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        
        onDispose {
            sensorManager.unregisterListener(sensorListener)
            toneGen?.release()
        }
    }

    val highFiveState by viewModel.highFiveState.collectAsState()
    val highFiveSession by viewModel.highFiveSession.collectAsState()
    val touchCount by viewModel.touchCount.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    
    // Animation states with force feedback
    var isAnimating by remember { mutableStateOf(false) }
    var currentTouchCount by remember { mutableStateOf(0) }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (currentTouchCount > 0) 0.9f else 0.4f,
        animationSpec = tween(durationMillis = 100),
        label = "alpha"
    )
    val animatedSize by animateFloatAsState(
        targetValue = when {
            currentTouchCount == 0 -> 260f  // Even smaller base size
            currentForce > 15f -> 320f
            currentForce > 10f -> 300f
            currentForce > 5f -> 280f
            else -> 270f + (currentTouchCount * 3f)  // Smaller increment per touch
        },
        animationSpec = tween(durationMillis = 50),
        label = "size"
    )
    val animatedScale by animateFloatAsState(
        targetValue = scaleEffect,
        animationSpec = tween(durationMillis = 50),
        label = "scale"
    )
    
    // Touch tracking state
    var lastTouchTime by remember { mutableStateOf(0L) }
    var isHighFiveComplete by remember { mutableStateOf(false) }
    var finalTouchCount by remember { mutableStateOf(0) }
    
    // Update touch count and manage display
    LaunchedEffect(touchCount) {
        val currentTime = System.currentTimeMillis()
        lastTouchTime = currentTime
    }
    
    // Reset animation after delay
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            delay(100)
            isAnimating = false
        }
    }
    
    // Track high five completion
    LaunchedEffect(highFiveState) {
        if (highFiveState is HighFiveState.Success) {
            isHighFiveComplete = true
            finalTouchCount = touchCount
        }
    }
    
    // Reset peak force after delay
    LaunchedEffect(peakForce) {
        if (peakForce > 0f) {
            delay(2000) // 2 seconds
            peakForce = 0f
        }
    }
    
    LaunchedEffect(partnerId) {
        Log.d("HighFiveScreen", "LaunchedEffect triggered for partnerId: $partnerId")
        if (currentUser?.id == partnerId) {
            viewModel.createHighFiveSession(partnerId)
        } else {
            viewModel.connectToUser(partnerId)
        }
        viewModel.onEnterHighFiveScreen()
    }

    DisposableEffect(Unit) {
        onDispose {
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
            // Main touchable area with hand outline
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp)  // Add horizontal padding
                        .pointerInput(Unit) {
                            detectMultiplePointers { count ->
                                currentTouchCount = count
                                if (count > 0) {
                                    isAnimating = true
                                    viewModel.incrementTouchCount()
                                    viewModel.initiateHighFive()
                                    
                                    // Trigger vibration based on current force
                                    vibrateOnImpact(currentForce)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Hand outline with enhanced animation and force feedback
                    Icon(
                        imageVector = Icons.Default.BackHand,
                        contentDescription = "High Five Hand",
                        modifier = Modifier
                            .size(animatedSize.dp)
                            .alpha(animatedAlpha)
                            .scale(animatedScale),
                        tint = when {
                            currentForce > 25f -> MaterialTheme.colorScheme.error
                            currentForce > 20f -> MaterialTheme.colorScheme.tertiary
                            currentForce > 15f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            currentTouchCount > 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }

            // Touch feedback display with force information
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isHighFiveComplete) {
                    Text(
                        text = "High Five Complete!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Max Force: ${String.format("%.1f", maxForce)}G",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "Total Touches: $finalTouchCount",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Touch feedback section
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = currentTouchCount.toString(),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = when (currentTouchCount) {
                                    0 -> "Touch to High Five!"
                                    1 -> "One Finger! 👆"
                                    2 -> "Peace! ✌️"
                                    3 -> "Nice! 🤟"
                                    4 -> "Getting There! 🖖"
                                    5 -> "Full Hand! 🖐"
                                    in 6..9 -> "Wow! $currentTouchCount Fingers! 🌟"
                                    else -> "Maximum Power! 🚀"
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        // Force feedback section (always visible)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (peakForce > 0f) {
                                Text(
                                    text = "Peak Force: ${String.format("%.1f", peakForce)}G",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = when {
                                        peakForce > 15f -> "SUPER HIGH FIVE! 💥"
                                        peakForce > 10f -> "Powerful! ⚡"
                                        peakForce > 5f -> "Good Force! 💪"
                                        else -> "Keep Going! 🔄"
                                    },
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            // Always show max force
                            if (maxForce > 0f) {
                                Text(
                                    text = "Max Force: ${String.format("%.1f", maxForce)}G",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                                )
                            }
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

suspend fun PointerInputScope.detectMultiplePointers(
    onTouchCountChanged: (Int) -> Unit
) {
    awaitEachGesture {
        try {
            do {
                val event = awaitPointerEvent()
                val touches = event.changes.count { it.pressed }
                onTouchCountChanged(touches)
            } while (event.changes.any { it.pressed })
        } finally {
            // Ensure we always reset the touch count when the gesture ends
            onTouchCountChanged(0)
        }
    }
}