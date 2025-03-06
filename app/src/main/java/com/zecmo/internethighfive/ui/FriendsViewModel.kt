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
import com.zecmo.internethighfive.data.HighFiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

class FriendsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "FriendsViewModel"
        private const val HEARTBEAT_INTERVAL = 1000L // 1 second
    }

    private val database = FirebaseDatabase.getInstance("https://internethighfive-zecmo-default-rtdb.firebaseio.com").reference
    private val userPreferences = UserPreferences(application)
    
    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _currentUserFriends = MutableStateFlow<List<String>>(emptyList())
    val currentUserFriends: StateFlow<List<String>> = _currentUserFriends.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private var heartbeatJob: Job? = null

    init {
        loadCurrentUser()
        loadAllUsers()
    }

    private fun startHeartbeat(userId: String) {
        stopHeartbeat() // Stop any existing heartbeat
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                try {
                    database.child("users").child(userId)
                        .updateChildren(mapOf("lastLoginTimestamp" to ServerValue.TIMESTAMP))
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in heartbeat", e)
                    delay(HEARTBEAT_INTERVAL) // Still delay on error to prevent rapid retries
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                userPreferences.userFlow.collect { credentials ->
                    if (credentials != null) {
                        Log.d(TAG, "Loading current user data for ID: ${credentials.id}")
                        if (_currentUserId.value != credentials.id) {
                            _currentUserId.value = credentials.id
                            startHeartbeat(credentials.id)
                        }
                        database.child("users").child(credentials.id)
                            .addValueEventListener(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    val user = snapshot.getValue(User::class.java)
                                    Log.d(TAG, "Current user data updated: handRaised=${user?.handRaised}, timestamp=${user?.raisedHandTimestamp}")
                                    _currentUser.value = user
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    Log.e(TAG, "Error loading current user", error.toException())
                                    _error.value = "Failed to load current user: ${error.message}"
                                }
                            })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadCurrentUser", e)
                _error.value = "Failed to load current user: ${e.message}"
            }
        }
    }

    private fun loadAllUsers() {
        Log.d(TAG, "Starting to load all users")
        _isLoading.value = true
        _error.value = null

        database.child("users")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val usersList = snapshot.children.mapNotNull { 
                            try {
                                it.getValue(User::class.java)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing user from snapshot: ${it.key}", e)
                                null
                            }
                        }
                        
                        Log.d(TAG, "Successfully loaded ${usersList.size} users")
                        _friends.value = usersList.sortedByDescending { it.isOnline }
                        _isLoading.value = false
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing users", e)
                        _error.value = "Error processing users: ${e.message ?: "Unknown error"}"
                        _isLoading.value = false
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Firebase error loading users: ${error.message}", error.toException())
                    _error.value = "Failed to load users: ${error.message}"
                    _isLoading.value = false
                }
            })
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                database.child("users")
                    .orderByChild("username")
                    .startAt(query.lowercase())
                    .endAt(query.lowercase() + "\uf8ff")
                    .limitToFirst(20)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val currentUserId = _currentUserId.value
                            val friendIds = _currentUserFriends.value
                            
                            val results = snapshot.children
                                .mapNotNull { it.getValue(User::class.java) }
                                .filter { it.id != currentUserId }
                                .sortedBy { user ->
                                    val onlineKey = if (user.isOnline) "0" else "1"
                                    val friendKey = if (friendIds.contains(user.id)) "0" else "1"
                                    "$onlineKey$friendKey${user.username}"
                                }
                            
                            _searchResults.value = results
                            _isLoading.value = false
                            Log.d(TAG, "Found ${results.size} users matching '$query' (${results.count { it.isOnline }} online)")
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Error searching users", error.toException())
                            _error.value = error.message
                            _isLoading.value = false
                        }
                    })
            } catch (e: Exception) {
                Log.e(TAG, "Error in search", e)
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }

    fun addFriend(userId: String) {
        val currentUserId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                // Add friend ID to current user's friend list
                val currentUserRef = database.child("users").child(currentUserId)
                val friendIds = _currentUserFriends.value.toMutableList()
                if (!friendIds.contains(userId)) {
                    friendIds.add(userId)
                    currentUserRef.child("friendIds").setValue(friendIds)
                }

                // Add current user ID to friend's friend list (reciprocal)
                val friendRef = database.child("users").child(userId)
                friendRef.child("friendIds").get().addOnSuccessListener { snapshot ->
                    val otherFriendIds = snapshot.children.mapNotNull { it.getValue(String::class.java) }.toMutableList()
                    if (!otherFriendIds.contains(currentUserId)) {
                        otherFriendIds.add(currentUserId)
                        friendRef.child("friendIds").setValue(otherFriendIds)
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun isFriend(userId: String): Boolean {
        return _currentUserFriends.value.contains(userId)
    }

    fun sendHighFive(friendId: String) {
        viewModelScope.launch {
            try {
                val currentUser = _currentUser.value ?: return@launch
                
                // Create a new high five entry
                val highFiveId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                val highFive = mapOf(
                    "id" to highFiveId,
                    "initiatorId" to currentUser.id,
                    "receiverId" to friendId,
                    "initiatorTimestamp" to timestamp,
                    "status" to "pending"
                )

                // Save to Firebase
                database.child("high_fives").child(highFiveId).setValue(highFive)

                // Send notification
                database.child("notifications").child(friendId).push().setValue(
                    mapOf(
                        "type" to "high_five_request",
                        "senderId" to currentUser.id,
                        "senderName" to currentUser.username,
                        "timestamp" to timestamp
                    )
                )

                // Set timeout to expire the high five if not completed
                launch {
                    delay(5000) // 5 seconds timeout
                    database.child("high_fives").child(highFiveId).get()
                        .addOnSuccessListener { snapshot ->
                            val status = snapshot.child("status").getValue(String::class.java)
                            if (status == "pending") {
                                database.child("high_fives").child(highFiveId)
                                    .updateChildren(mapOf("status" to "expired"))
                            }
                        }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending high five", e)
                _error.value = "Failed to send high five: ${e.message}"
            }
        }
    }

    fun updateHandRaisedStatus(isRaised: Boolean) {
        val currentUserId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                val updates = mapOf(
                    "handRaised" to isRaised,
                    "raisedHandTimestamp" to if (isRaised) ServerValue.TIMESTAMP else 0L
                )
                database.child("users").child(currentUserId).updateChildren(updates)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error updating hand raised status", e)
                        _error.value = "Failed to update hand raised status: ${e.message}"
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateHandRaisedStatus", e)
                _error.value = e.message
            }
        }
    }

    fun createHighFiveSession(partnerId: String) {
        viewModelScope.launch {
            try {
                val currentUser = _currentUser.value ?: return@launch
                Log.d(TAG, "Checking for existing high five session with partner: $partnerId")
                
                // First check if the partner has an active session
                database.child("users").child(partnerId).child("currentHighFiveSession")
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val existingSessionId = snapshot.getValue(String::class.java)
                        
                        if (!existingSessionId.isNullOrEmpty()) {
                            // Partner has an active session, join it
                            Log.d(TAG, "Joining existing session: $existingSessionId")
                            database.child("high_five_sessions").child(existingSessionId)
                                .get()
                                .addOnSuccessListener { sessionSnapshot ->
                                    val session = sessionSnapshot.getValue(HighFiveSession::class.java)
                                    if (session != null && session.partnerId.isEmpty()) {
                                        // Update session with partner info
                                        val updates = mapOf(
                                            "partnerId" to currentUser.id,
                                            "partnerUsername" to currentUser.username,
                                            "lastUpdated" to ServerValue.TIMESTAMP
                                        )
                                        
                                        // Update the session with partner info
                                        database.child("high_five_sessions").child(existingSessionId)
                                            .updateChildren(updates)
                                            .addOnSuccessListener {
                                                // Set currentHighFiveSession for the joining partner
                                                database.child("users").child(currentUser.id)
                                                    .child("currentHighFiveSession")
                                                    .setValue(existingSessionId)
                                                    .addOnSuccessListener {
                                                        Log.d(TAG, "Successfully joined existing session")
                                                    }
                                                    .addOnFailureListener { e ->
                                                        Log.e(TAG, "Failed to set currentHighFiveSession for partner", e)
                                                        _error.value = "Failed to join session. Please try again."
                                                    }
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "Failed to update session with partner info", e)
                                                _error.value = "Failed to join session. Please try again."
                                            }
                                    } else {
                                        _error.value = "Session is no longer available"
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to get session details", e)
                                    _error.value = "Failed to join session. Please try again."
                                }
                        } else {
                            // Partner doesn't have an active session, create a new one
                            Log.d(TAG, "Creating new high five session")
                            val sessionId = UUID.randomUUID().toString()
                            val newSession = HighFiveSession(
                                id = sessionId,
                                initiatorId = currentUser.id,
                                initiatorUsername = currentUser.username,
                                partnerId = partnerId,
                                partnerUsername = "",  // Will be updated when partner joins
                                initiatorTimestamp = System.currentTimeMillis(),
                                partnerTimestamp = 0L,
                                lastUpdated = System.currentTimeMillis(),
                                completed = false,
                                quality = ""
                            )
                            
                            // Set currentHighFiveSession for initiator
                            database.child("users").child(currentUser.id)
                                .child("currentHighFiveSession")
                                .setValue(sessionId)
                                .addOnSuccessListener {
                                    // Create the session
                                    database.child("high_five_sessions").child(sessionId)
                                        .setValue(newSession)
                                        .addOnSuccessListener {
                                            Log.d(TAG, "Successfully created high five session")
                                            
                                            // Send notification to partner
                                            database.child("notifications").child(partnerId).push().setValue(
                                                mapOf(
                                                    "type" to "high_five_request",
                                                    "senderId" to currentUser.id,
                                                    "senderName" to currentUser.username,
                                                    "timestamp" to System.currentTimeMillis()
                                                )
                                            )
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e(TAG, "Failed to create high five session", e)
                                            _error.value = "Failed to create session. Please try again."
                                        }
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to set currentHighFiveSession for initiator", e)
                                    _error.value = "Failed to create session. Please try again."
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to check partner's currentHighFiveSession", e)
                        _error.value = "Error connecting to partner. Please try again."
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in createHighFiveSession", e)
                _error.value = "Error: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopHeartbeat()
    }
} 