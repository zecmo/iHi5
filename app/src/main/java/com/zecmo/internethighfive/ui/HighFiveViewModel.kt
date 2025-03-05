package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.HighFive
import com.zecmo.internethighfive.data.UserPreferences
import com.zecmo.internethighfive.data.HighFiveSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.util.UUID

class HighFiveViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "HighFiveViewModel"
        private const val HIGH_FIVE_TIMEOUT = 5000L // 5 seconds to complete high five
        const val MAX_HIGH_FIVE_TIME_DIFF = 2000L // 2 seconds maximum time difference for a successful high five
    }

    private val database = FirebaseDatabase.getInstance("https://internethighfive-zecmo-default-rtdb.firebaseio.com").reference
    private val userPreferences = UserPreferences(application)
    private var activeHighFiveListener: ValueEventListener? = null
    private var activeSessionListener: ValueEventListener? = null

    private val _highFiveState = MutableStateFlow<HighFiveState>(HighFiveState.Idle)
    val highFiveState = _highFiveState.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser = _currentUser.asStateFlow()

    private val _connectedUser = MutableStateFlow<User?>(null)
    val connectedUser = _connectedUser.asStateFlow()

    private val _highFiveSession = MutableStateFlow<HighFiveSession?>(null)
    val highFiveSession = _highFiveSession.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _inAppNotification = MutableStateFlow<String?>(null)
    val inAppNotification = _inAppNotification.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                if (credentials != null) {
                    // Load full user data from Firebase
                    database.child("users").child(credentials.id)
                        .addValueEventListener(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val user = snapshot.getValue(User::class.java)?.copy(id = credentials.id)
                                _currentUser.value = user
                                listenForHighFives(user?.id)
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Log.e(TAG, "Error loading current user", error.toException())
                            }
                        })
                } else {
                    _currentUser.value = null
                    _connectedUser.value = null
                }
            }
        }
    }

    private fun loadConnectedUser(id: String) {
        Log.d(TAG, "Loading connected user with ID: $id")
        database.child("users").child(id)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val user = snapshot.getValue(User::class.java)?.copy(id = id)
                    Log.d(TAG, "Connected user loaded: ${user?.username}")
                    _connectedUser.value = user
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error loading connected user", error.toException())
                    showInAppNotification("Error loading connected user. Please try again.")
                }
            })
    }

    private fun sendHighFiveNotification(partnerId: String, type: String, data: Map<String, String>) {
        Log.d(TAG, "Sending notification to $partnerId: type=$type, data=$data")
        database.child("notifications").child(partnerId).push().setValue(
            mapOf(
                "type" to type,
                "timestamp" to ServerValue.TIMESTAMP
            ) + data
        ).addOnSuccessListener {
            Log.d(TAG, "Successfully sent notification")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to send notification", e)
        }
    }

    fun dismissNotification() {
        _inAppNotification.value = null
    }

    private fun showInAppNotification(message: String) {
        _inAppNotification.value = message
    }

    fun connectToUser(partnerId: String) {
        Log.d(TAG, "Connecting to user with ID: $partnerId")
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first()
                if (currentUser != null) {
                    Log.d(TAG, "Current user found: ${currentUser.username}")
                    
                    // Always order user IDs alphabetically to ensure consistent session IDs
                    val (firstUserId, secondUserId) = listOf(currentUser.id, partnerId).sorted()
                    val sessionId = "${firstUserId}_${secondUserId}"
                    
                    // First, check if a session already exists between these two users
                    database.child("high_five_sessions").child(sessionId)
                        .get()
                        .addOnSuccessListener { existingSessionSnapshot ->
                            val existingSession = existingSessionSnapshot.getValue(HighFiveSession::class.java)
                            
                            if (existingSession != null) {
                                // Session exists, just join it
                                Log.d(TAG, "Joining existing session: $sessionId")
                                loadConnectedUser(partnerId)
                                listenForHighFiveSession(sessionId)
                                return@addOnSuccessListener
                            }
                            
                            // If no direct session exists, check for any other active sessions
                            val fiveMinutesAgo = (System.currentTimeMillis() - 300000).toDouble()
                            database.child("high_five_sessions")
                                .orderByChild("lastUpdated")
                                .startAt(fiveMinutesAgo)
                                .get()
                                .addOnSuccessListener { snapshot ->
                                    val existingSessions = mutableListOf<HighFiveSession>()
                                    snapshot.children.forEach { sessionSnapshot ->
                                        sessionSnapshot.getValue(HighFiveSession::class.java)?.let { session ->
                                            existingSessions.add(session)
                                        }
                                    }
                                    
                                    // Check if either user is in any other active session
                                    val userInOtherSession = existingSessions.any { session ->
                                        (session.userId1 == currentUser.id || session.userId2 == currentUser.id ||
                                         session.userId1 == partnerId || session.userId2 == partnerId) &&
                                        session.id != sessionId
                                    }
                                    
                                    if (userInOtherSession) {
                                        showInAppNotification("Cannot connect: One of the users is already in another session")
                                        return@addOnSuccessListener
                                    }
                                    
                                    // Create new session with consistent user ordering
                                    val newSession = HighFiveSession(
                                        id = sessionId,
                                        userId1 = firstUserId,
                                        userId2 = secondUserId,
                                        isUser1Ready = false,
                                        isUser2Ready = false,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                    
                                    database.child("high_five_sessions").child(sessionId)
                                        .setValue(newSession)
                                        .addOnSuccessListener {
                                            Log.d(TAG, "Successfully created high five session")
                                            loadConnectedUser(partnerId)
                                            listenForHighFiveSession(sessionId)
                                            
                                            // Send notification to partner
                                            sendHighFiveNotification(
                                                partnerId = partnerId,
                                                type = "high_five_request",
                                                data = mapOf("senderName" to currentUser.username)
                                            )
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e(TAG, "Failed to create high five session", e)
                                            showInAppNotification("Failed to create session. Please try again.")
                                        }
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Error checking for existing sessions", e)
                                    showInAppNotification("Error creating session. Please try again.")
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error checking for existing session", e)
                            showInAppNotification("Error connecting to session. Please try again.")
                        }
                } else {
                    Log.e(TAG, "No current user found when trying to connect")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to user", e)
                showInAppNotification("Error connecting to user. Please try again.")
            }
        }
    }

    private fun listenForHighFiveSession(sessionId: String) {
        // Remove any existing session listener
        activeSessionListener?.let {
            database.child("high_five_sessions").child(sessionId).removeEventListener(it)
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(HighFiveSession::class.java)
                val previousSession = _highFiveSession.value
                _highFiveSession.value = session
                
                // Update local ready state based on session
                val currentUserId = _currentUser.value?.id
                if (currentUserId != null && session != null) {
                    val isCurrentUserReady = if (currentUserId == session.userId1) {
                        session.isUser1Ready
                    } else {
                        session.isUser2Ready
                    }
                    _isReady.value = isCurrentUserReady
                    
                    // Check if partner's ready state changed
                    val isPartnerReady = if (currentUserId == session.userId1) {
                        session.isUser2Ready
                    } else {
                        session.isUser1Ready
                    }
                    
                    val wasPartnerReady = if (previousSession != null) {
                        if (currentUserId == previousSession.userId1) {
                            previousSession.isUser2Ready
                        } else {
                            previousSession.isUser1Ready
                        }
                    } else {
                        false
                    }
                    
                    // Show notification if partner's ready state changed
                    if (isPartnerReady != wasPartnerReady) {
                        if (isPartnerReady) {
                            showInAppNotification("Your partner is ready! Get ready too!")
                            // Send FCM notification
                            val partnerId = if (currentUserId == session.userId1) session.userId2 else session.userId1
                            val connectedUser = _connectedUser.value
                            if (connectedUser != null) {
                                database.child("notifications").child(partnerId).push().setValue(
                                    mapOf(
                                        "type" to "high_five_ready",
                                        "partnerName" to connectedUser.username,
                                        "timestamp" to ServerValue.TIMESTAMP
                                    )
                                )
                            }
                        } else {
                            showInAppNotification("Your partner is no longer ready")
                        }
                    }
                    
                    // Show notification if both are ready
                    if (isPartnerReady && isCurrentUserReady) {
                        showInAppNotification("Both players ready! Tap to high five!")
                    }
                } else if (session == null) {
                    // Session was deleted or doesn't exist
                    _highFiveSession.value = null
                    _isReady.value = false
                    showInAppNotification("Session ended")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening for high five session", error.toException())
            }
        }
        
        database.child("high_five_sessions").child(sessionId)
            .addValueEventListener(listener)
            
        activeSessionListener = listener
    }

    fun setReady(ready: Boolean) {
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first()
                val session = _highFiveSession.value
                
                if (currentUser != null && session != null) {
                    // Determine if we're user1 or user2 based on the sorted order
                    val isUser1 = currentUser.id == session.userId1
                    
                    // Update ready state in session
                    val updates = mutableMapOf<String, Any>()
                    if (isUser1) {
                        updates["isUser1Ready"] = ready
                    } else {
                        updates["isUser2Ready"] = ready
                    }
                    updates["lastUpdated"] = ServerValue.TIMESTAMP
                    
                    database.child("high_five_sessions").child(session.id)
                        .updateChildren(updates)
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully updated ready state in session")
                            _isReady.value = ready
                            
                            // Show notification
                            if (ready) {
                                showInAppNotification("You're ready! Waiting for partner...")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to update ready state", e)
                            showInAppNotification("Failed to update ready state. Please try again.")
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting ready state", e)
                showInAppNotification("Error setting ready state. Please try again.")
            }
        }
    }

    fun initiateHighFive() {
        val currentUser = _currentUser.value ?: return
        val session = _highFiveSession.value ?: return

        if (!_isReady.value) {
            _highFiveState.value = HighFiveState.Error("You need to be ready first!")
            return
        }

        // Check if partner is ready using session
        val currentUserId = currentUser.id
        val isPartnerReady = if (currentUserId == session.userId1) {
            session.isUser2Ready
        } else {
            session.isUser1Ready
        }

        if (!isPartnerReady) {
            _highFiveState.value = HighFiveState.Error("Your friend needs to be ready!")
            return
        }

        if (_highFiveState.value !is HighFiveState.Idle) {
            return
        }

        viewModelScope.launch {
            try {
                _highFiveState.value = HighFiveState.Waiting
                
                val highFiveId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                val partnerId = if (currentUserId == session.userId1) {
                    session.userId2
                } else {
                    session.userId1
                }
                
                val highFive = HighFive(
                    id = highFiveId,
                    initiatorId = currentUser.id,
                    receiverId = partnerId,
                    initiatorTimestamp = timestamp,
                    status = "pending"
                )

                database.child("high_fives").child(highFiveId).setValue(highFive)
                
                // Set timeout
                launch {
                    delay(HIGH_FIVE_TIMEOUT)
                    database.child("high_fives").child(highFiveId)
                        .updateChildren(mapOf("status" to "expired"))
                }
            } catch (e: Exception) {
                _highFiveState.value = HighFiveState.Error(e.message ?: "Failed to initiate high five")
            }
        }
    }

    private fun listenForHighFives(id: String?) {
        if (id == null) return
        
        activeHighFiveListener?.let {
            database.child("high_fives").removeEventListener(it)
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (highFiveSnapshot in snapshot.children) {
                    val highFive = highFiveSnapshot.getValue(HighFive::class.java) ?: continue
                    
                    when {
                        // If we're the receiver and it's pending, respond with our timestamp
                        highFive.receiverId == id && highFive.isPending() -> {
                            respondToHighFive(highFive)
                        }
                        
                        // If we're either user and it's matched, check the quality
                        (highFive.initiatorId == id || highFive.receiverId == id) && 
                        highFive.isMatched() -> {
                            val timeDiff = highFive.getTimeDifference()
                            if (timeDiff <= MAX_HIGH_FIVE_TIME_DIFF) {
                                val quality = calculateHighFiveQuality(timeDiff)
                                database.child("high_fives").child(highFive.id)
                                    .updateChildren(mapOf(
                                        "status" to "completed",
                                        "quality" to quality
                                    ))
                                _highFiveState.value = HighFiveState.Success(quality)
                            } else {
                                database.child("high_fives").child(highFive.id)
                                    .updateChildren(mapOf("status" to "expired"))
                                _highFiveState.value = HighFiveState.Error("Too slow! Try again!")
                            }
                        }
                        
                        // Handle expired high fives
                        (highFive.initiatorId == id || highFive.receiverId == id) && 
                        highFive.isExpired() -> {
                            _highFiveState.value = HighFiveState.Error("High five expired!")
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening for high fives", error.toException())
            }
        }
        
        database.child("high_fives")
            .orderByChild("status")
            .equalTo("pending")
            .addValueEventListener(listener)
            
        activeHighFiveListener = listener
    }

    private fun respondToHighFive(highFive: HighFive) {
        if (!_isReady.value) return
        
        val timestamp = System.currentTimeMillis()
        database.child("high_fives").child(highFive.id)
            .updateChildren(mapOf(
                "receiverTimestamp" to timestamp,
                "status" to "matched"
            ))
    }

    private fun calculateHighFiveQuality(timeDiff: Long): Float {
        return when {
            timeDiff < 100 -> 1.0f  // Perfect!
            timeDiff < 300 -> 0.8f  // Great!
            timeDiff < 500 -> 0.6f  // Good
            timeDiff < 800 -> 0.4f  // Ok
            else -> 0.2f            // Meh
        }
    }

    fun isPartnerReady(): Boolean {
        val session = _highFiveSession.value ?: return false
        val currentUserId = _currentUser.value?.id ?: return false
        
        return if (currentUserId == session.userId1) {
            session.isUser2Ready
        } else {
            session.isUser1Ready
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up all listeners
        activeHighFiveListener?.let { listener ->
            database.child("high_fives").removeEventListener(listener)
        }
        
        activeSessionListener?.let { listener ->
            val session = _highFiveSession.value
            if (session != null) {
                database.child("high_five_sessions").child(session.id).removeEventListener(listener)
            }
        }
        
        // Reset ready state in session when leaving
        viewModelScope.launch {
            try {
                val currentUser = _currentUser.value
                val session = _highFiveSession.value
                if (currentUser != null && session != null) {
                    val updates = mutableMapOf<String, Any>()
                    if (currentUser.id == session.userId1) {
                        updates["isUser1Ready"] = false
                    } else {
                        updates["isUser2Ready"] = false
                    }
                    updates["lastUpdated"] = ServerValue.TIMESTAMP
                    
                    database.child("high_five_sessions").child(session.id)
                        .updateChildren(updates)
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully reset ready state in session")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to reset ready state", e)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting ready state", e)
            }
        }
    }
}

sealed class HighFiveState {
    object Idle : HighFiveState()
    object Waiting : HighFiveState()
    data class Success(val quality: Float) : HighFiveState()
    data class Error(val message: String) : HighFiveState()
} 