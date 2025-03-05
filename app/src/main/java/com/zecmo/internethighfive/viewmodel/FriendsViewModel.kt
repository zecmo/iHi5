package com.zecmo.internethighfive.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import com.zecmo.internethighfive.notification.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HighFiveRequest(
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val timestamp: Long = System.currentTimeMillis()
)

class FriendsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val database = FirebaseDatabase.getInstance().reference
    private val userPreferences = UserPreferences(application)
    private var friendsListener: ValueEventListener? = null
    private var usersListener: ValueEventListener? = null
    private var highFiveRequestsListener: ValueEventListener? = null

    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentUserFriends = MutableStateFlow<List<String>>(emptyList())
    val currentUserFriends: StateFlow<List<String>> = _currentUserFriends.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _highFiveRequests = MutableStateFlow<List<HighFiveRequest>>(emptyList())
    val highFiveRequests: StateFlow<List<HighFiveRequest>> = _highFiveRequests.asStateFlow()

    init {
        NotificationHelper.createNotificationChannel(context)
        loadCurrentUser()
        listenForHighFiveRequests()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                userPreferences.userFlow.collect { credentials ->
                    _currentUserId.value = credentials?.id
                    if (credentials?.id != null) {
                        Log.d(TAG, "Loading friends for user: ${credentials.id}")
                        listenForFriends(credentials.id)
                    } else {
                        Log.e(TAG, "No user credentials found")
                        _error.value = "No user credentials found"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading current user", e)
                _error.value = "Error loading current user: ${e.message}"
            }
        }
    }

    private fun listenForFriends(id: String) {
        // Remove existing listener if any
        friendsListener?.let { listener ->
            database.child("users").child(id).child("friendIds").removeEventListener(listener)
        }

        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendIds = snapshot.children.mapNotNull { it.getValue(String::class.java) }
                _currentUserFriends.value = friendIds
                loadFriendsData(friendIds)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening for friends", error.toException())
                _error.value = "Failed to load friends"
            }
        }

        database.child("users").child(id).child("friendIds")
            .addValueEventListener(friendsListener!!)
    }

    private fun loadFriendsData(friendIds: List<String>) {
        _isLoading.value = true
        
        // Remove existing listener if any
        usersListener?.let { listener ->
            database.child("users").removeEventListener(listener)
        }

        usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val allUsers = snapshot.children.mapNotNull { 
                    it.getValue(User::class.java)?.copy(id = it.key ?: return@mapNotNull null)
                }
                
                // Sort users by online status and username
                val sortedUsers = allUsers.sortedWith(
                    compareByDescending<User> { it.isOnline }
                        .thenByDescending { it.lastLoginTimestamp }
                        .thenBy { it.username.lowercase() }
                )
                
                _friends.value = sortedUsers
                updateSearchResults(sortedUsers, _searchQuery.value)
                _isLoading.value = false
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error loading friends data", error.toException())
                _error.value = "Failed to load friends data"
                _isLoading.value = false
            }
        }

        database.child("users").addValueEventListener(usersListener!!)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        updateSearchResults(_friends.value, query)
    }

    private fun updateSearchResults(users: List<User>, query: String) {
        if (query.isBlank()) {
            _searchResults.value = users
            return
        }

        val currentUserId = _currentUserId.value
        _searchResults.value = users.filter { user ->
            user.username.contains(query, ignoreCase = true) && user.id != currentUserId
        }
    }

    fun sendHighFiveRequest(receiverId: String) {
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.getUser() ?: return@launch
                val request = HighFiveRequest(
                    senderId = currentUser.id,
                    senderName = currentUser.username,
                    receiverId = receiverId
                )
                
                database.child("highFiveRequests")
                    .child(receiverId)
                    .child(currentUser.id)
                    .setValue(request)
                    .addOnSuccessListener {
                        Log.d(TAG, "High five request sent to $receiverId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error sending high five request", e)
                        Toast.makeText(
                            context,
                            "Failed to send high five request",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending high five request", e)
                Toast.makeText(
                    context,
                    "Failed to send high five request",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun listenForHighFiveRequests() {
        viewModelScope.launch {
            val currentUser = userPreferences.getUser() ?: return@launch
            
            // Remove existing listener if any
            highFiveRequestsListener?.let { listener ->
                database.child("highFiveRequests").child(currentUser.id)
                    .removeEventListener(listener)
            }

            highFiveRequestsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val requests = snapshot.children.mapNotNull { 
                        it.getValue(HighFiveRequest::class.java) 
                    }
                    _highFiveRequests.value = requests
                    
                    // Show notification for new requests
                    requests.forEach { request ->
                        // Show toast
                        Toast.makeText(
                            context,
                            "${request.senderName} wants to high five!",
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Show notification
                        NotificationHelper.showHighFiveRequest(
                            context = context,
                            senderId = request.senderId,
                            senderName = request.senderName,
                            notificationId = request.senderId.hashCode()
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error listening for high five requests", error.toException())
                }
            }

            database.child("highFiveRequests")
                .child(currentUser.id)
                .addValueEventListener(highFiveRequestsListener!!)
        }
    }

    fun addFriend(friendId: String) {
        viewModelScope.launch {
            try {
                val currentUser = userPreferences.getUser() ?: return@launch
                
                // Add friend to current user's friend list
                database.child("users")
                    .child(currentUser.id)
                    .child("friendIds")
                    .child(friendId)
                    .setValue(friendId)
                    .addOnSuccessListener {
                        Log.d(TAG, "Added friend $friendId to user ${currentUser.id}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error adding friend", e)
                        Toast.makeText(
                            context,
                            "Failed to add friend",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                // Add current user to friend's friend list
                database.child("users")
                    .child(friendId)
                    .child("friendIds")
                    .child(currentUser.id)
                    .setValue(currentUser.id)
                    .addOnSuccessListener {
                        Log.d(TAG, "Added user ${currentUser.id} to friend $friendId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error adding user to friend's list", e)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding friend", e)
                Toast.makeText(
                    context,
                    "Failed to add friend",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun isFriend(id: String): Boolean {
        return _currentUserFriends.value.contains(id)
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up all listeners
        val currentUserId = _currentUserId.value
        if (currentUserId != null) {
            friendsListener?.let { listener ->
                database.child("users").child(currentUserId).child("friendIds")
                    .removeEventListener(listener)
            }
        }
        
        usersListener?.let { listener ->
            database.child("users").removeEventListener(listener)
        }
        
        highFiveRequestsListener?.let { listener ->
            _currentUserId.value?.let { id ->
                database.child("highFiveRequests").child(id)
                    .removeEventListener(listener)
            }
        }
    }

    companion object {
        private const val TAG = "FriendsViewModel"
    }
} 