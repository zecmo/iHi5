package com.zecmo.internethighfive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    authViewModel: AuthViewModel = viewModel()
) {
    var username by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Internet High Five!",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = { 
                val cleanInput = it.replace("\n", "")
                username = cleanInput
                isError = false
                authViewModel.checkUsername(cleanInput)
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
                    if (username.isNotBlank()) {
                        authViewModel.login(username)
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
                if (username.isNotBlank()) {
                    authViewModel.login(username)
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