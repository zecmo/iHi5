package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zecmo.internethighfive.SupabaseClient
import com.zecmo.internethighfive.data.HighFiveSession
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class FriendsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "FriendsViewModel"
        private const val HEARTBEAT_INTERVAL = 5_000L // 5 seconds
    }

    private val supabase = SupabaseClient.client
    private val userPreferences = UserPreferences(application)

    // All non-self users — used by Find Fivers screen
    private val _allUsers = MutableStateFlow<List<User>>(emptyList())
    val allUsers: StateFlow<List<User>> = _allUsers.asStateFlow()

    // Only users who are mutual friends — used by Lobby
    private val _friends = MutableStateFlow<List<User>>(emptyList())
    val friends: StateFlow<List<User>> = _friends.asStateFlow()

    private val _friendIds = MutableStateFlow<List<String>>(emptyList())
    val friendIds: StateFlow<List<String>> = _friendIds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private var heartbeatJob: Job? = null
    private var usersChannel: io.github.jan.supabase.realtime.RealtimeChannel? = null

    // Local cache of fields we actually care about for UI changes.
    // Realtime sends the full row on every UPDATE (including heartbeat last_login_at writes),
    // so we compare against this to avoid reloading on irrelevant changes.
    private val cachedHandRaised = mutableMapOf<String, Boolean>()
    private val cachedCurrentSession = mutableMapOf<String, String>()

    init {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                if (credentials != null && credentials.id != _currentUserId.value) {
                    _currentUserId.value = credentials.id
                    // Reset any stale hand/session state left over from a previous crash or kill
                    try {
                        supabase.from("users").update({
                            set("hand_raised", false)
                            set("current_session", "")
                        }) { filter { eq("id", credentials.id) } }
                    } catch (e: Exception) {
                        Log.e(TAG, "launch reset failed", e)
                    }
                    storeFcmToken(credentials.id)
                    startHeartbeat(credentials.id)
                    loadCurrentUser(credentials.id)
                    loadAllUsers()
                    subscribeToUserChanges()
                    loadFriendIds(credentials.id)
                }
            }
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────

    private fun startHeartbeat(userId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                try {
                    supabase.from("users").update({
                        set("last_login_at", System.currentTimeMillis())
                    }) {
                        filter { eq("id", userId) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed", e)
                }
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    // ── Data loading ───────────────────────────────────────────────────────────

    private suspend fun loadCurrentUser(userId: String) {
        try {
            val user = supabase.from("users")
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<User>()
            _currentUser.value = user
        } catch (e: Exception) {
            Log.e(TAG, "loadCurrentUser failed", e)
        }
    }

    private fun loadAllUsers() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val selfId = _currentUserId.value
                val users = supabase.from("users")
                    .select()
                    .decodeList<User>()
                    .filter { it.id != selfId }          // never show self
                    .sortedByDescending { it.isOnline }
                _allUsers.value = users
                rebuildFriendsList(users)
                // Seed cache so first realtime event has a baseline to compare against
                users.forEach { u ->
                    cachedHandRaised[u.id] = u.handRaised
                    cachedCurrentSession[u.id] = u.currentSession
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAllUsers failed", e)
                _error.value = "Failed to load users: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun rebuildFriendsList(users: List<User> = _allUsers.value) {
        val ids = _friendIds.value
        _friends.value = users.filter { it.id in ids }
    }

    private suspend fun loadFriendIds(userId: String) {
        try {
            // Friends I added
            val iAdded = supabase.from("friendships")
                .select(Columns.list("friend_id")) {
                    filter { eq("user_id", userId) }
                }
                .decodeList<FriendRow>()
                .map { it.friendId }

            // Friends who added me
            val addedMe = supabase.from("friendships")
                .select(Columns.list("user_id")) {
                    filter { eq("friend_id", userId) }
                }
                .decodeList<UserRow>()
                .map { it.userId }

            _friendIds.value = (iAdded + addedMe).distinct()
            rebuildFriendsList()
        } catch (e: Exception) {
            Log.e(TAG, "loadFriendIds failed", e)
        }
    }

    // ── Realtime subscription ──────────────────────────────────────────────────

    private fun subscribeToUserChanges() {
        viewModelScope.launch {
            try {
                // Unsubscribe and remove old channel before creating new one
                usersChannel?.let {
                    try { it.unsubscribe() } catch (_: Exception) {}
                    supabase.realtime.removeChannel(it)
                }
                val channel = supabase.channel("public:users:${System.currentTimeMillis()}")
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "users"
                }.onEach { action ->
                    if (action is PostgresAction.Update) {
                        val record = action.record
                        val updatedId = record["id"]?.jsonPrimitive?.contentOrNull ?: return@onEach
                        val newHandRaised = record["hand_raised"]?.jsonPrimitive?.booleanOrNull ?: false
                        val newSession = record["current_session"]?.jsonPrimitive?.contentOrNull ?: ""

                        val handChanged = cachedHandRaised[updatedId] != newHandRaised
                        val sessionChanged = cachedCurrentSession[updatedId] != newSession

                        cachedHandRaised[updatedId] = newHandRaised
                        cachedCurrentSession[updatedId] = newSession

                        if (handChanged || sessionChanged) {
                            Log.d(TAG, "relevant change for $updatedId: hand=$newHandRaised session=$newSession")
                            loadAllUsers()
                        }
                        // Refresh current user's own data only if it's their row
                        if (updatedId == _currentUserId.value) {
                            _currentUserId.value?.let { loadCurrentUser(it) }
                        }
                    } else {
                        // Insert or delete — always reload
                        loadAllUsers()
                        _currentUserId.value?.let { loadCurrentUser(it) }
                    }
                }.launchIn(viewModelScope)
                channel.subscribe()
                usersChannel = channel
            } catch (e: Exception) {
                Log.e(TAG, "subscribeToUserChanges failed", e)
            }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    fun addFriend(friendId: String) {
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                // Only insert current user's direction — can't insert on behalf of the other user
                supabase.from("friendships").upsert(
                    buildJsonObject {
                        put("user_id", currentId)
                        put("friend_id", friendId)
                    }
                )
                loadFriendIds(currentId)
            } catch (e: Exception) {
                Log.e(TAG, "addFriend failed", e)
                _error.value = "Failed to add friend: ${e.message}"
            }
        }
    }

    fun isFriend(userId: String): Boolean = _friendIds.value.contains(userId)

    // ── FCM token ──────────────────────────────────────────────────────────────

    private fun storeFcmToken(userId: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            viewModelScope.launch {
                try {
                    supabase.from("users").update({
                        set("fcm_token", token)
                    }) { filter { eq("id", userId) } }
                    Log.d(TAG, "FCM token stored")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to store FCM token", e)
                }
            }
        }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun notifyFriends(type: String) {
        val recipientIds = _friendIds.value.ifEmpty { return }
        val sender = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                supabase.functions.invoke(
                    function = "send-notification",
                    body = buildJsonObject {
                        put("type", type)
                        put("recipientIds", buildJsonArray { recipientIds.forEach { add(it) } })
                        put("senderName", sender.username)
                        put("senderId", sender.id)
                    }
                )
                Log.d(TAG, "notifyFriends sent: type=$type recipients=${recipientIds.size}")
            } catch (e: Exception) {
                Log.e(TAG, "notifyFriends failed", e)
            }
        }
    }

    private fun notifyFriend(friendId: String) {
        val sender = _currentUser.value ?: return
        viewModelScope.launch {
            try {
                supabase.functions.invoke(
                    function = "send-notification",
                    body = buildJsonObject {
                        put("type", "invite")
                        put("recipientIds", buildJsonArray { add(friendId) })
                        put("senderName", sender.username)
                        put("senderId", sender.id)
                    }
                )
                Log.d(TAG, "notifyFriend sent to $friendId")
            } catch (e: Exception) {
                Log.e(TAG, "notifyFriend failed", e)
            }
        }
    }

    fun inviteFriend(friendId: String) {
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                supabase.from("users").update({
                    set("hand_raised", true)
                    set("raised_hand_at", System.currentTimeMillis())
                }) { filter { eq("id", currentId) } }
                notifyFriend(friendId)
            } catch (e: Exception) {
                Log.e(TAG, "inviteFriend failed", e)
            }
        }
    }

    fun updateHandRaisedStatus(isRaised: Boolean) {
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                supabase.from("users").update({
                    set("hand_raised", isRaised)
                    set("raised_hand_at", if (isRaised) System.currentTimeMillis() else 0L)
                }) {
                    filter { eq("id", currentId) }
                }
                if (isRaised) notifyFriends("hand_raised")
            } catch (e: Exception) {
                Log.e(TAG, "updateHandRaisedStatus failed", e)
            }
        }
    }

    fun createHighFiveSession(partnerId: String) {
        viewModelScope.launch {
            try {
                val currentUser = _currentUser.value ?: return@launch
                val sessionId = UUID.randomUUID().toString()

                val newSession = HighFiveSession(
                    id = sessionId,
                    initiatorId = currentUser.id,
                    initiatorUsername = currentUser.username,
                    partnerId = partnerId,
                    partnerUsername = "",
                    initiatorTimestamp = System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis()
                )
                supabase.from("high_five_sessions").insert(newSession)

                // Mark current session on user row
                supabase.from("users").update({
                    set("current_session", sessionId)
                }) {
                    filter { eq("id", currentUser.id) }
                }

                // Push notification to the invited friend
                notifyFriend(partnerId)
            } catch (e: Exception) {
                Log.e(TAG, "createHighFiveSession failed", e)
                _error.value = "Error: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        heartbeatJob?.cancel()
        viewModelScope.launch {
            try { usersChannel?.unsubscribe() } catch (_: Exception) {}
        }
    }
}

@kotlinx.serialization.Serializable
private data class FriendRow(
    @kotlinx.serialization.SerialName("friend_id") val friendId: String
)

@kotlinx.serialization.Serializable
private data class UserRow(
    @kotlinx.serialization.SerialName("user_id") val userId: String
)
