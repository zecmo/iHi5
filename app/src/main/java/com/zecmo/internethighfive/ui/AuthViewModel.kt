package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zecmo.internethighfive.SupabaseClient
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AuthViewModel"
    }

    private val supabase = SupabaseClient.client
    private val userPreferences = UserPreferences(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _usernameStatus = MutableStateFlow<UsernameStatus>(UsernameStatus.Unknown)
    val usernameStatus: StateFlow<UsernameStatus> = _usernameStatus.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                _authState.value = if (credentials != null) {
                    AuthState.LoggedIn(credentials.id, credentials.username)
                } else {
                    AuthState.LoggedOut
                }
            }
        }
    }

    fun login(username: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                Log.d(TAG, "Attempting login for: $username")

                // Check if username already exists
                val existing = supabase.from("users")
                    .select(Columns.list("id", "username")) {
                        filter { eq("username", username) }
                    }
                    .decodeSingleOrNull<User>()

                val userId: String
                if (existing != null) {
                    Log.d(TAG, "Found existing user: ${existing.id}")
                    userId = existing.id
                    // Ensure we have a valid Supabase Auth session
                    if (supabase.auth.currentSessionOrNull() == null) {
                        supabase.auth.signInAnonymously()
                    }
                } else {
                    // New user — sign up anonymously, then create their profile
                    Log.d(TAG, "Creating new user: $username")
                    supabase.auth.signInAnonymously()
                    val authUser = supabase.auth.currentUserOrNull()
                        ?: throw IllegalStateException("Anonymous sign-in failed")
                    userId = authUser.id

                    supabase.from("users").insert(
                        User(id = userId, username = username, email = "")
                    )
                    Log.d(TAG, "Created new user: $userId")
                }

                userPreferences.saveUser(userId, username)
                _authState.value = AuthState.LoggedIn(userId, username)

            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                _authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                supabase.auth.signOut()
                userPreferences.clearUser()
                _authState.value = AuthState.LoggedOut
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed", e)
                _authState.value = AuthState.Error("Logout failed: ${e.message}")
            }
        }
    }

    fun checkUsername(username: String) {
        if (username.isBlank()) {
            _usernameStatus.value = UsernameStatus.Unknown
            return
        }
        viewModelScope.launch {
            try {
                val existing = supabase.from("users")
                    .select(Columns.list("id", "username")) {
                        filter { eq("username", username) }
                    }
                    .decodeSingleOrNull<User>()

                _usernameStatus.value = if (existing != null) {
                    UsernameStatus.Existing(username)
                } else {
                    UsernameStatus.New(username)
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkUsername failed", e)
                _usernameStatus.value = UsernameStatus.Unknown
            }
        }
    }
}

sealed class AuthState {
    object Loading : AuthState()
    object LoggedOut : AuthState()
    data class LoggedIn(val id: String, val username: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

sealed class UsernameStatus {
    object Unknown : UsernameStatus()
    data class Existing(val username: String) : UsernameStatus()
    data class New(val username: String) : UsernameStatus()
}
