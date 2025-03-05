package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ServerValue
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AuthViewModel"
    }

    private val userPreferences = UserPreferences(application)
    private val database = FirebaseDatabase.getInstance("https://internethighfive-zecmo-default-rtdb.firebaseio.com").reference

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
            try {
                Log.d(TAG, "Attempting to login user: $username")
                val existingUser = findUserByUsername(username)
                
                if (existingUser != null) {
                    Log.d(TAG, "Found existing user: ${existingUser.username} (${existingUser.id})")
                    userPreferences.saveUser(existingUser.id, username)
                    _authState.value = AuthState.LoggedIn(existingUser.id, username)
                } else {
                    val id = UUID.randomUUID().toString()
                    Log.d(TAG, "Creating new user with ID: $id")
                    val newUser = User(
                        id = id,
                        username = username,
                        email = ""
                    )
                    
                    database.child("users").child(id).setValue(newUser)
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully created new user")
                        }.addOnFailureListener { e ->
                            Log.e(TAG, "Failed to create new user", e)
                        }
                    userPreferences.saveUser(id, username)
                    _authState.value = AuthState.LoggedIn(id, username)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login failed", e)
                _authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting logout process")
                userPreferences.clearUser()
                _authState.value = AuthState.LoggedOut
                Log.d(TAG, "Logout completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
                _authState.value = AuthState.Error("Logout failed: ${e.message}")
            }
        }
    }

    private suspend fun findUserByUsername(username: String): User? = suspendCancellableCoroutine { continuation ->
        val usersRef = database.child("users")
        val query = usersRef.orderByChild("username").equalTo(username)
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val user = snapshot.children.firstOrNull()?.getValue(User::class.java)
                continuation.resume(user)
            }

            override fun onCancelled(error: DatabaseError) {
                continuation.resumeWithException(error.toException())
            }
        }

        query.addListenerForSingleValueEvent(listener)

        continuation.invokeOnCancellation {
            query.removeEventListener(listener)
        }
    }

    fun checkUsername(username: String) {
        if (username.isBlank()) {
            _usernameStatus.value = UsernameStatus.Unknown
            return
        }
        
        viewModelScope.launch {
            try {
                val existingUser = findUserByUsername(username)
                _usernameStatus.value = if (existingUser != null) {
                    UsernameStatus.Existing(username)
                } else {
                    UsernameStatus.New(username)
                }
            } catch (e: Exception) {
                _usernameStatus.value = UsernameStatus.Unknown
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
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