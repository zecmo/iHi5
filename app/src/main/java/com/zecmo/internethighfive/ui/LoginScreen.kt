package com.zecmo.internethighfive.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.widget.Toast
import com.zecmo.internethighfive.R
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    authViewModel: AuthViewModel = viewModel()
) {
    var username by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val authState by authViewModel.authState.collectAsState()
    val usernameStatus by authViewModel.usernameStatus.collectAsState()

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.LoggedIn -> onLoginSuccess()
            is AuthState.Error -> {
                isError = true
                Toast.makeText(context, (authState as AuthState.Error).message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent
    ) { padding ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            Image(
                painter = painterResource(id = R.drawable.hi5_logo),
                contentDescription = "Internet High Five",
                modifier = Modifier
                    .size(220.dp)
                    .padding(bottom = 16.dp),
                contentScale = ContentScale.Fit
            )

            Text(
                text = "Internet High Five!",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Text(
                text = "Celebrate that thing that just happened",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        OutlinedTextField(
            value = username,
            onValueChange = {
                val cleanInput = it.replace("\n", "").trimStart()
                username = cleanInput
                isError = false
                authViewModel.checkUsername(cleanInput.trim())
            },
            label = { Text("Username") },
            isError = isError,
            singleLine = true,
            supportingText = {
                when (usernameStatus) {
                    is UsernameStatus.Existing -> Text(
                        "Welcome back!",
                        color = MaterialTheme.colorScheme.primary
                    )
                    is UsernameStatus.New -> Text(
                        "New user - account will be created",
                        color = MaterialTheme.colorScheme.secondary
                    )
                    UsernameStatus.Unknown -> {
                        if (isError) {
                            Text(
                                "Please enter a valid username",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val trimmed = username.trim()
                    if (trimmed.isNotBlank()) {
                        keyboardController?.hide()
                        authViewModel.login(trimmed)
                    } else {
                        isError = true
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        Button(
            onClick = {
                val trimmed = username.trim()
                if (trimmed.isNotBlank()) {
                    keyboardController?.hide()
                    authViewModel.login(trimmed)
                } else {
                    isError = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (usernameStatus) {
                    is UsernameStatus.Existing -> "Login"
                    is UsernameStatus.New -> "Create Account"
                    UsernameStatus.Unknown -> "Continue"
                }
            )
        }
        }
    }
    }
}