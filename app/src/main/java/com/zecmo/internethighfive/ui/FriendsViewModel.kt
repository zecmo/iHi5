package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zecmo.internethighfive.SupabaseClient
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _addFriendLoading = MutableStateFlow(false)
    val addFriendLoading: StateFlow<Boolean> = _addFriendLoading.asStateFlow()

    private val _addFriendSuccess = MutableStateFlow(false)
    val addFriendSuccess: StateFlow<Boolean> = _addFriendSuccess.asStateFlow()

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
    private val cachedLastLoginAt = mutableMapOf<String, Long>()

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
                    loadData()
                    subscribeToUserChanges()
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

    // Loads users + friend IDs concurrently; shows spinner until both complete.
    private fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val selfId = _currentUserId.value ?: return@launch
                coroutineScope {
                    val usersDeferred = async {
                        supabase.from("users").select().decodeList<User>()
                            .filter { it.id != selfId }
                            .sortedByDescending { it.isOnline }
                    }
                    val friendIdsDeferred = async { fetchFriendIds(selfId) }
                    val users = usersDeferred.await()
                    val ids = friendIdsDeferred.await()
                    _allUsers.value = users
                    _friendIds.value = ids
                    rebuildFriendsList(users)
                    users.forEach { u ->
                        cachedHandRaised[u.id] = u.handRaised
                        cachedCurrentSession[u.id] = u.currentSession
                        cachedLastLoginAt[u.id] = u.lastLoginAt
                    }
                    // Cache own row so realtime updates to self don't look like new changes
                    _currentUser.value?.let { me ->
                        cachedHandRaised[me.id] = me.handRaised
                        cachedCurrentSession[me.id] = me.currentSession
                        cachedLastLoginAt[me.id] = me.lastLoginAt
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadData failed", e)
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Silent refresh triggered by realtime — no spinner.
    private fun loadAllUsers() {
        viewModelScope.launch {
            try {
                val selfId = _currentUserId.value
                val users = supabase.from("users").select().decodeList<User>()
                    .filter { it.id != selfId }
                    .sortedByDescending { it.isOnline }
                _allUsers.value = users
                rebuildFriendsList(users)
                users.forEach { u ->
                    cachedHandRaised[u.id] = u.handRaised
                    cachedCurrentSession[u.id] = u.currentSession
                    cachedLastLoginAt[u.id] = u.lastLoginAt
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadAllUsers failed", e)
            }
        }
    }

    private fun rebuildFriendsList(users: List<User> = _allUsers.value) {
        val ids = _friendIds.value
        _friends.value = users.filter { it.id in ids }
    }

    private suspend fun fetchFriendIds(userId: String): List<String> {
        val iAdded = supabase.from("friendships")
            .select(Columns.list("friend_id")) { filter { eq("user_id", userId) } }
            .decodeList<FriendRow>().map { it.friendId }
        val addedMe = supabase.from("friendships")
            .select(Columns.list("user_id")) { filter { eq("friend_id", userId) } }
            .decodeList<UserRow>().map { it.userId }
        return (iAdded + addedMe).distinct()
    }

    private suspend fun reloadFriendIds() {
        try {
            val userId = _currentUserId.value ?: return
            _friendIds.value = fetchFriendIds(userId)
            rebuildFriendsList()
        } catch (e: Exception) {
            Log.e(TAG, "reloadFriendIds failed", e)
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
                        val newLastLoginAt = record["last_login_at"]?.jsonPrimitive?.longOrNull ?: 0L

                        val handChanged = cachedHandRaised[updatedId] != newHandRaised
                        val sessionChanged = cachedCurrentSession[updatedId] != newSession
                        val wasOnline = cachedLastLoginAt[updatedId]
                            ?.let { System.currentTimeMillis() - it < User.ONLINE_THRESHOLD } ?: false
                        val isNowOnline = System.currentTimeMillis() - newLastLoginAt < User.ONLINE_THRESHOLD
                        val onlineStatusChanged = wasOnline != isNowOnline

                        cachedHandRaised[updatedId] = newHandRaised
                        cachedCurrentSession[updatedId] = newSession
                        cachedLastLoginAt[updatedId] = newLastLoginAt

                        if (handChanged || sessionChanged || onlineStatusChanged) {
                            Log.d(TAG, "relevant change for $updatedId: hand=$newHandRaised session=$newSession online=$isNowOnline")
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
                supabase.from("friendships").upsert(buildJsonObject {
                    put("user_id", currentId)
                    put("friend_id", friendId)
                })
                reloadFriendIds()
            } catch (e: Exception) {
                Log.e(TAG, "addFriend failed", e)
                _error.value = "Failed to add friend: ${e.message}"
            }
        }
    }

    fun addFriendByUsername(username: String) {
        if (username.isBlank()) { _error.value = "Username cannot be empty"; return }
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            _addFriendLoading.value = true
            _error.value = null
            _addFriendSuccess.value = false
            try {
                val found = supabase.from("users")
                    .select { filter { eq("username", username) } }
                    .decodeSingleOrNull<User>()
                if (found == null) { _error.value = "User '$username' not found"; return@launch }
                if (found.id == currentId) { _error.value = "You can't add yourself"; return@launch }
                supabase.from("friendships").upsert(buildJsonObject {
                    put("user_id", currentId); put("friend_id", found.id)
                })
                supabase.from("friendships").upsert(buildJsonObject {
                    put("user_id", found.id); put("friend_id", currentId)
                })
                reloadFriendIds()
                _addFriendSuccess.value = true
            } catch (e: Exception) {
                Log.e(TAG, "addFriendByUsername failed", e)
                _error.value = e.message ?: "Unknown error"
            } finally {
                _addFriendLoading.value = false
            }
        }
    }

    fun clearAddFriendState() {
        _addFriendSuccess.value = false
        _error.value = null
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

    private fun notifyFriends(type: String, message: String = "") {
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
                        put("message", message)
                    }
                )
                Log.d(TAG, "notifyFriends sent: type=$type recipients=${recipientIds.size}")
            } catch (e: Exception) {
                Log.e(TAG, "notifyFriends failed", e)
            }
        }
    }

    fun inviteFriend(friendId: String, message: String = "") {
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                supabase.from("users").update({
                    set("hand_raised", true)
                    set("raised_hand_at", System.currentTimeMillis())
                }) { filter { eq("id", currentId) } }
                // Notification is sent AFTER session creation in HighFiveViewModel.openSession()
                // to avoid a race where the notification arrives before the session exists.
            } catch (e: Exception) {
                Log.e(TAG, "inviteFriend failed", e)
            }
        }
    }

    fun updateHandRaisedStatus(isRaised: Boolean, message: String = "") {
        val currentId = _currentUserId.value ?: return
        viewModelScope.launch {
            try {
                supabase.from("users").update({
                    set("hand_raised", isRaised)
                    set("raised_hand_at", if (isRaised) System.currentTimeMillis() else 0L)
                }) {
                    filter { eq("id", currentId) }
                }
                if (isRaised) notifyFriends("hand_raised", message)
            } catch (e: Exception) {
                Log.e(TAG, "updateHandRaisedStatus failed", e)
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
