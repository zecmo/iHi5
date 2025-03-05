package com.zecmo.internethighfive.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import com.zecmo.internethighfive.data.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class AddUserViewModel : ViewModel() {
    companion object {
        private const val TAG = "AddUserViewModel"
    }

    private val database = FirebaseDatabase.getInstance("https://internethighfive-zecmo-default-rtdb.firebaseio.com").reference

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSuccess = MutableStateFlow(false)
    val isSuccess: StateFlow<Boolean> = _isSuccess.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        testFirebaseConnection()
    }

    private fun testFirebaseConnection() {
        Log.d(TAG, "Testing Firebase connection...")
        val connectedRef = database.child(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                _isConnected.value = connected
                Log.d(TAG, "Firebase connection status: ${if (connected) "CONNECTED" else "DISCONNECTED"}")
                
                if (!connected) {
                    _error.value = "Not connected to Firebase. Please check your internet connection."
                } else {
                    _error.value = null
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase connection test failed", error.toException())
                _isConnected.value = false
                _error.value = "Failed to connect to Firebase: ${error.message}"
            }
        })
    }

    fun addUser(username: String) {
        if (!_isConnected.value) {
            _error.value = "Not connected to Firebase. Please check your internet connection."
            return
        }

        if (username.isBlank()) {
            _error.value = "Username cannot be empty"
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                _isSuccess.value = false
                
                Log.d(TAG, "Starting user creation for username: $username")
                
                // First check if username already exists
                val existingUser = checkUsernameExists(username)
                if (existingUser) {
                    _error.value = "Username already exists"
                    _isLoading.value = false
                    return@launch
                }
                
                val userId = UUID.randomUUID().toString()
                val newUser = User(
                    id = userId,
                    username = username,
                    lastLoginTimestamp = System.currentTimeMillis()
                )

                Log.d(TAG, "Attempting to add user to Firebase: $newUser")
                try {
                    addUserToFirebase(newUser)
                    Log.d(TAG, "Successfully added user: $username")
                    _isSuccess.value = true
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding user to Firebase", e)
                    _error.value = "Failed to add user: ${e.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in addUser", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun checkUsernameExists(username: String): Boolean = suspendCoroutine { continuation ->
        database.child("users")
            .orderByChild("username")
            .equalTo(username)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val exists = snapshot.exists()
                    Log.d(TAG, "Username '$username' exists: $exists")
                    continuation.resume(exists)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error checking username existence", error.toException())
                    continuation.resume(false)
                }
            })
    }

    private suspend fun addUserToFirebase(user: User): Boolean = suspendCoroutine { continuation ->
        database.child("users").child(user.id).setValue(user)
            .addOnSuccessListener {
                Log.d(TAG, "Firebase setValue completed successfully")
                continuation.resume(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase setValue failed", e)
                continuation.resumeWithException(e)
            }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any listeners if needed
        database.child(".info/connected").removeEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }
} 