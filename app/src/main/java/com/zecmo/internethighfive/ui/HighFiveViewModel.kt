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

    private val _highFiveSession = MutableStateFlow<HighFiveSession?>(null)
    val highFiveSession = _highFiveSession.asStateFlow()

    private val _touchCount = MutableStateFlow(0)
    val touchCount = _touchCount.asStateFlow()

    private val _inAppNotification = MutableStateFlow<String?>(null)
    val inAppNotification = _inAppNotification.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                if (credentials != null) {
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
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error loading connected user", error.toException())
                    showInAppNotification("Error loading connected user. Please try again.")
                }
            })
    }

    private fun sendHighFiveNotification(partnerId: String, type: String, data: Map<String, String>) {
        Log.d(TAG, "Sending notification to $partnerId: type=$type, data=$data")
        val notificationData = HashMap<String, Any>()
        notificationData["type"] = type
        notificationData["timestamp"] = ServerValue.TIMESTAMP
        notificationData.putAll(data)
        
        database.child("notifications").child(partnerId).push().setValue(notificationData)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully sent notification")
            }
            .addOnFailureListener { e ->
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
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first() ?: return@launch
                Log.d(TAG, "Attempting to connect to user: $partnerId")
                
                // First check if the partner has an active session
                database.child("users").child(partnerId).child("currentHighFiveSession")
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val existingSessionId = snapshot.getValue(String::class.java)
                        
                        if (!existingSessionId.isNullOrEmpty()) {
                            // Partner has an active session, join it
                            Log.d(TAG, "Found existing session: $existingSessionId")
                            
                            // Update the session with partner info
                            val sessionUpdates = mapOf(
                                "partnerId" to currentUser.id,
                                "partnerUsername" to currentUser.username,
                                "lastUpdated" to ServerValue.TIMESTAMP
                            )
                            
                            // Update the session with partner info
                            database.child("high_five_sessions").child(existingSessionId)
                                .updateChildren(sessionUpdates)
                                .addOnSuccessListener {
                                    // Set currentHighFiveSession for the joining partner
                                    database.child("users").child(currentUser.id)
                                        .child("currentHighFiveSession")
                                        .setValue(existingSessionId)
                                        .addOnSuccessListener {
                                            Log.d(TAG, "Successfully joined session")
                                            _highFiveState.value = HighFiveState.WaitingForPartner
                                            listenForHighFiveSession(existingSessionId)
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
                            Log.e(TAG, "Partner has no active session")
                            _error.value = "Partner is not ready for a high five"
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to check partner's currentHighFiveSession", e)
                        _error.value = "Error connecting to partner. Please try again."
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectToUser", e)
                _error.value = "Error: ${e.message}"
            }
        }
    }

    private fun listenForHighFiveSession(sessionId: String) {
        activeSessionListener?.let {
            database.child("high_five_sessions").child(sessionId).removeEventListener(it)
        }
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(HighFiveSession::class.java)
                val previousSession = _highFiveSession.value
                _highFiveSession.value = session
                
                val currentUserId = _currentUser.value?.id
                if (currentUserId != null && session != null) {
                    // Update partnerUsername if we're the partner
                    if (currentUserId == session.partnerId && session.partnerUsername.isEmpty()) {
                        val updates = mapOf(
                            "partnerUsername" to (_currentUser.value?.username ?: "")
                        )
                        database.child("high_five_sessions").child(sessionId)
                            .updateChildren(updates)
                    }
                    
                    // Show notification if session is completed
                    if (session.completed == true && previousSession?.completed != true) {
                        showInAppNotification("High five completed! Quality: ${session.quality}")
                    }
                } else if (session == null) {
                    // Session was deleted or doesn't exist
                    _highFiveSession.value = null
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

    fun initiateHighFive() {
        val currentUser = _currentUser.value ?: return
        val session = _highFiveSession.value ?: return

        if (_highFiveState.value !is HighFiveState.Idle) {
            return
        }

        viewModelScope.launch {
            try {
                _highFiveState.value = HighFiveState.Waiting
                
                val highFiveId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                val partnerId = if (currentUser.id == session.initiatorId) {
                    session.partnerId
                } else {
                    session.initiatorId
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
        val timestamp = System.currentTimeMillis()
        val timeDiff = highFive.getTimeDifference()
        val quality = calculateHighFiveQuality(timeDiff)
        
        // Update high five session with completion
        val session = _highFiveSession.value
        if (session != null) {
            val updates = mapOf(
                "partnerTimestamp" to timestamp,
                "completed" to true,
                "quality" to quality,
                "lastUpdated" to ServerValue.TIMESTAMP
            )
            
            database.child("high_five_sessions").child(session.id)
                .updateChildren(updates)
        }
        
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

    fun onEnterHighFiveScreen() {
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first()
                val currentUserId = currentUser?.id ?: run {
                    Log.e(TAG, "No current user ID available for hand raised status update")
                    return@launch
                }
                
                Log.d(TAG, "Updating hand raised status on enter for user: $currentUserId")
                val updates = mapOf(
                    "handRaised" to true,
                    "raisedHandTimestamp" to ServerValue.TIMESTAMP
                )
                
                Log.d(TAG, "Sending Firebase update with values: $updates")
                database.child("users").child(currentUserId).updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully updated hand raised status on enter")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update hand raised status on enter", e)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating hand raised status on enter", e)
            }
        }
    }

    fun onExitHighFiveScreen() {
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first()
                val session = _highFiveSession.value
                
                // Clear currentHighFiveSession and handRaised status for the user
                val updates = mapOf(
                    "currentHighFiveSession" to "",
                    "handRaised" to false
                )
                database.child("users").child(currentUser?.id ?: "").updateChildren(updates)
                
                // If user is initiator and session is not completed, delete the session
                if (session != null && !session.completed && session.initiatorId == currentUser?.id) {
                    Log.d(TAG, "Initiator leaving, deleting incomplete session: ${session.id}")
                    database.child("high_five_sessions").child(session.id).removeValue()
                    // Also clear partner's currentHighFiveSession and handRaised status
                    if (session.partnerId.isNotEmpty()) {
                        database.child("users").child(session.partnerId).updateChildren(updates)
                    }
                }
                
                _highFiveSession.value = null
                _highFiveState.value = HighFiveState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error in onExitHighFiveScreen", e)
            }
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
        
        // Reset hand raised status when ViewModel is cleared
        onExitHighFiveScreen()
    }

    fun incrementTouchCount() {
        _touchCount.value++
    }

    fun createHighFiveSession(partnerId: String) {
        Log.d(TAG, "Creating new high five session with partner: $partnerId")
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.userFlow.first()
                if (currentUser != null) {
                    Log.d(TAG, "Current user found: ${currentUser.username}")
                    
                    // Create new session
                    val sessionId = UUID.randomUUID().toString()
                    val newSession = HighFiveSession(
                        id = sessionId,
                        initiatorId = currentUser.id,
                        initiatorUsername = currentUser.username,
                        partnerId = "",  // Will be updated when partner joins
                        partnerUsername = "",  // Will be updated when partner joins
                        initiatorTimestamp = System.currentTimeMillis(),
                        partnerTimestamp = 0L,
                        lastUpdated = System.currentTimeMillis(),
                        completed = false,
                        quality = ""
                    )
                    
                    // First create the session
                    database.child("high_five_sessions").child(sessionId)
                        .setValue(newSession)
                        .addOnSuccessListener {
                            // Then set currentHighFiveSession for initiator
                            database.child("users").child(currentUser.id)
                                .child("currentHighFiveSession")
                                .setValue(sessionId)
                                .addOnSuccessListener {
                                    Log.d(TAG, "Successfully created high five session")
                                    _highFiveSession.value = newSession
                                    _highFiveState.value = HighFiveState.WaitingForPartner
                                    listenForHighFiveSession(sessionId)
                                    
                                    // Send notification to partner
                                    sendHighFiveNotification(
                                        partnerId = partnerId,
                                        type = "high_five_request",
                                        data = mapOf("senderName" to currentUser.username)
                                    )
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to set currentHighFiveSession for initiator", e)
                                    _error.value = "Failed to create session. Please try again."
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to create high five session", e)
                            _error.value = "Failed to create session. Please try again."
                        }
                } else {
                    Log.e(TAG, "No current user found when trying to create session")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating high five session", e)
                _error.value = "Error creating session. Please try again."
            }
        }
    }
}

sealed class HighFiveState {
    object Idle : HighFiveState()
    object Waiting : HighFiveState()
    object WaitingForPartner : HighFiveState()
    data class Success(val quality: Float) : HighFiveState()
    data class Error(val message: String) : HighFiveState()
} 