package com.zecmo.internethighfive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUserScreen(
    onNavigateBack: () -> Unit,
    viewModel: FriendsViewModel = viewModel(factory = ViewModelProvider.AndroidViewModelFactory.getInstance(LocalContext.current.applicationContext as android.app.Application))
) {
    var username by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val isLoading by viewModel.addFriendLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isSuccess by viewModel.addFriendSuccess.collectAsState()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            Toast.makeText(context, "Friend added!", Toast.LENGTH_SHORT).show()
            viewModel.clearAddFriendState()
            onNavigateBack()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            isError = true
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Friend") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !isLoading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Add a Friend",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            OutlinedTextField(
                value = username,
                onValueChange = {
                    val cleanInput = it.replace("\n", "")
                    username = cleanInput
                    isError = false
                },
                label = { Text("Username") },
                isError = isError,
                singleLine = true,
                supportingText = {
                    if (isError) {
                        Text(
                            text = error ?: "Please enter a valid username",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (username.isNotBlank()) {
                            viewModel.addFriendByUsername(username)
                        } else {
                            isError = true
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .focusRequester(focusRequester),
                enabled = !isLoading
            )

            Button(
                onClick = {
                    if (username.isNotBlank()) {
                        viewModel.addFriendByUsername(username)
                    } else {
                        isError = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Add Friend")
                }
            }
        }
    }
}
