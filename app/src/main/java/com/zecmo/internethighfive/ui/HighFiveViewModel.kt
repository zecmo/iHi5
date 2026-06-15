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
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

class HighFiveViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "HighFiveViewModel"
        const val MAX_HIGH_FIVE_DIFF_MS = 3_000L  // 3 s grace window
        private const val HIGH_FIVE_TIMEOUT_MS = 8_000L  // wait up to 8 s for partner tap
    }

    private val supabase = SupabaseClient.client
    private val userPreferences = UserPreferences(application)

    private val _highFiveState = MutableStateFlow<HighFiveState>(HighFiveState.Idle)
    val highFiveState: StateFlow<HighFiveState> = _highFiveState.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _highFiveSession = MutableStateFlow<HighFiveSession?>(null)
    val highFiveSession: StateFlow<HighFiveSession?> = _highFiveSession.asStateFlow()

    private val _touchCount = MutableStateFlow(0)
    val touchCount: StateFlow<Int> = _touchCount.asStateFlow()

    // Incremented to 1 the moment both players are confirmed connected.
    // The screen uses this as a LaunchedEffect key to start the countdown.
    private val _bothConnectedEvent = MutableStateFlow(0)
    val bothConnectedEvent: StateFlow<Int> = _bothConnectedEvent.asStateFlow()

    private val _inAppNotification = MutableStateFlow<String?>(null)
    val inAppNotification: StateFlow<String?> = _inAppNotification.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var sessionChannel: RealtimeChannel? = null

    init {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                if (credentials != null) {
                    val user = supabase.from("users")
                        .select { filter { eq("id", credentials.id) } }
                        .decodeSingleOrNull<User>()
                    _currentUser.value = user
                }
            }
        }
    }

    // ── Screen lifecycle ───────────────────────────────────────────────────────

    fun onEnterHighFiveScreen() {
        _highFiveState.value = HighFiveState.Idle
        _touchCount.value = 0
        viewModelScope.launch {
            val userId = _currentUser.value?.id ?: return@launch
            try {
                supabase.from("users").update({
                    set("hand_raised", true)
                    set("raised_hand_at", System.currentTimeMillis())
                }) {
                    filter { eq("id", userId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onEnterHighFiveScreen update failed", e)
            }
        }
    }

    fun onExitHighFiveScreen() {
        val currentUser = _currentUser.value ?: return
        val session = _highFiveSession.value
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    supabase.from("users").update({
                        set("hand_raised", false)
                        set("current_session", "")
                    }) { filter { eq("id", currentUser.id) } }

                    if (session != null && !session.completed && session.initiatorId == currentUser.id) {
                        supabase.from("high_five_sessions").delete {
                            filter { eq("id", session.id) }
                        }
                        if (!session.partnerId.isNullOrEmpty()) {
                            supabase.from("users").update({
                                set("hand_raised", false)
                                set("current_session", "")
                            }) { filter { eq("id", session.partnerId) } }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onExitHighFiveScreen failed", e)
                }
                cleanupChannels()
            }
            _highFiveSession.value = null
            _highFiveState.value = HighFiveState.Idle
            _bothConnectedEvent.value = 0
        }
    }

    // ── Session management ───────────────────────���─────────────────────────────

    fun createHighFiveSession(partnerId: String) {
        viewModelScope.launch {
            val currentUser = _currentUser.value ?: return@launch
            try {
                val sessionId = UUID.randomUUID().toString()
                val session = HighFiveSession(
                    id = sessionId,
                    initiatorId = currentUser.id,
                    initiatorUsername = currentUser.username,
                    partnerId = partnerId,
                    initiatorTimestamp = 0L,
                    lastUpdated = System.currentTimeMillis()
                )
                supabase.from("high_five_sessions").insert(session)
                supabase.from("users").update({
                    set("current_session", sessionId)
                }) {
                    filter { eq("id", currentUser.id) }
                }
                _highFiveSession.value = session
                _highFiveState.value = HighFiveState.WaitingForPartner
                subscribeToSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "createHighFiveSession failed", e)
                _error.value = "Failed to create session: ${e.message}"
            }
        }
    }

    fun connectToUser(partnerId: String) {
        viewModelScope.launch {
            val currentUser = _currentUser.value ?: return@launch
            Log.d(TAG, "connectToUser: currentUser=${currentUser.id} partnerId=$partnerId")
            try {
                val sessions = supabase.from("high_five_sessions")
                    .select {
                        filter {
                            eq("initiator_id", partnerId)
                            eq("completed", false)
                        }
                    }
                    .decodeList<HighFiveSession>()
                Log.d(TAG, "connectToUser: found ${sessions.size} sessions for initiator $partnerId: ${sessions.map { "id=${it.id} partner=${it.partnerId}" }}")

                // Only join sessions with no partner yet AND no tap recorded
                // (initiatorTimestamp > 0 means stale data from old code or a previous round)
                val session = sessions.firstOrNull { it.partnerId.isNullOrEmpty() && it.initiatorTimestamp == 0L }

                if (session == null) {
                    Log.w(TAG, "connectToUser: no open session found for $partnerId")
                    _error.value = "Partner is not ready — make sure they raised their hand first"
                    _highFiveState.value = HighFiveState.Error("Partner is not ready — make sure they raised their hand first")
                    return@launch
                }
                Log.d(TAG, "connectToUser: joining session ${session.id}")

                supabase.from("high_five_sessions").update({
                    set("partner_id", currentUser.id)
                    set("partner_username", currentUser.username)
                    set("last_updated", System.currentTimeMillis())
                }) {
                    filter { eq("id", session.id) }
                }
                supabase.from("users").update({
                    set("current_session", session.id)
                }) {
                    filter { eq("id", currentUser.id) }
                }

                // Fetch the updated session so partner_id is populated — triggers countdown
                val updatedSession = supabase.from("high_five_sessions")
                    .select { filter { eq("id", session.id) } }
                    .decodeSingleOrNull<HighFiveSession>() ?: session

                _highFiveSession.value = updatedSession
                _highFiveState.value = HighFiveState.WaitingForPartner
                subscribeToSession(session.id)
                // Joiner already knows both are connected — start countdown immediately
                _bothConnectedEvent.value = 1
            } catch (e: Exception) {
                Log.e(TAG, "connectToUser failed", e)
                _error.value = "Error connecting: ${e.message}"
            }
        }
    }

    // ── Realtime subscription ──────────────────────────────────────────────────

    private fun subscribeToSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionChannel?.let {
                    try { it.unsubscribe() } catch (_: Exception) {}
                    supabase.realtime.removeChannel(it)
                }
                val channel = supabase.channel("session:$sessionId:${System.currentTimeMillis()}")
                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "high_five_sessions"
                }.onEach { _: PostgresAction.Update ->
                    val updated = supabase.from("high_five_sessions")
                        .select { filter { eq("id", sessionId) } }
                        .decodeSingleOrNull<HighFiveSession>() ?: return@onEach

                    val previous = _highFiveSession.value
                    _highFiveSession.value = updated

                    when {
                        updated.completed -> {
                            if (previous?.completed != true) {
                                _highFiveState.value = HighFiveState.Success(
                                    qualityLabelToFloat(updated.quality)
                                )
                            }
                        }
                        // Partner just joined — fire the local countdown signal on the initiator
                        previous?.partnerId.isNullOrEmpty() && !updated.partnerId.isNullOrEmpty()
                            && _currentUser.value?.id == updated.initiatorId -> {
                            _bothConnectedEvent.value = 1
                        }
                        // Both taps recorded — score them
                        updated.initiatorTimestamp > 0L
                            && updated.partnerTimestamp > 0L
                            && previous?.completed != true -> {
                            scoreSession(updated)
                        }
                    }
                }.launchIn(viewModelScope)
                channel.subscribe()
                sessionChannel = channel

                // Catch-up fetch: if partner_id was set before our WebSocket was
                // active, fire the countdown signal now so we don't get stuck.
                val current = supabase.from("high_five_sessions")
                    .select { filter { eq("id", sessionId) } }
                    .decodeSingleOrNull<HighFiveSession>()
                if (current != null && !current.partnerId.isNullOrEmpty()
                    && _bothConnectedEvent.value == 0) {
                    Log.d(TAG, "catch-up: partner already joined, firing bothConnectedEvent")
                    _highFiveSession.value = current
                    _bothConnectedEvent.value = 1
                }
            } catch (e: Exception) {
                Log.e(TAG, "subscribeToSession failed", e)
            }
        }
    }

    // ── High five tap mechanics ────────────────────────────────────────────────

    fun initiateHighFive() {
        if (_highFiveState.value != HighFiveState.Idle) return
        val currentUser = _currentUser.value ?: return
        val session = _highFiveSession.value ?: return

        viewModelScope.launch {
            _highFiveState.value = HighFiveState.Waiting
            val isInitiator = currentUser.id == session.initiatorId
            try {
                // Use server clock via RPC — eliminates device clock skew between
                // two phones, which can be 300–800 ms even on NTP-synced devices.
                supabase.postgrest.rpc(
                    "record_tap",
                    buildJsonObject {
                        put("session_id", session.id)
                        put("is_initiator", isInitiator)
                    }
                )
                // Fallback: if partner never taps, show timeout locally
                launch {
                    delay(HIGH_FIVE_TIMEOUT_MS)
                    if (_highFiveState.value == HighFiveState.Waiting) {
                        _highFiveState.value = HighFiveState.Error("Timed out — try again!")
                    }
                }
            } catch (e: Exception) {
                _highFiveState.value = HighFiveState.Error(e.message ?: "Failed to tap")
            }
        }
    }

    private suspend fun scoreSession(session: HighFiveSession) {
        val timeDiff = kotlin.math.abs(session.initiatorTimestamp - session.partnerTimestamp)
        Log.d(TAG, "scoreSession: initiator=${session.initiatorTimestamp} partner=${session.partnerTimestamp} diff=${timeDiff}ms")
        val quality = calculateQuality(timeDiff)
        val qualityLabel = qualityLabel(quality)

        if (timeDiff > MAX_HIGH_FIVE_DIFF_MS) {
            _highFiveState.value = HighFiveState.Error("Too slow! Try again!")
            return
        }

        _highFiveState.value = HighFiveState.Success(quality)
        // Initiator writes the result — partner learns via realtime completed=true
        if (_currentUser.value?.id == session.initiatorId) {
            try {
                supabase.from("high_five_sessions").update({
                    set("completed", true)
                    set("quality", qualityLabel)
                    set("last_updated", System.currentTimeMillis())
                }) { filter { eq("id", session.id) } }
            } catch (e: Exception) {
                Log.e(TAG, "scoreSession complete failed", e)
            }
        }
    }

    private fun calculateQuality(timeDiff: Long): Float = when {
        timeDiff < 100  -> 1.0f
        timeDiff < 300  -> 0.8f
        timeDiff < 500  -> 0.6f
        timeDiff < 800  -> 0.4f
        else            -> 0.2f
    }

    private fun qualityLabel(quality: Float): String = when {
        quality >= 1.0f -> "Perfect!"
        quality >= 0.8f -> "Great!"
        quality >= 0.6f -> "Good"
        quality >= 0.4f -> "Ok"
        else            -> "Meh"
    }

    private fun qualityLabelToFloat(label: String): Float = when (label) {
        "Perfect!" -> 1.0f
        "Great!"   -> 0.8f
        "Good"     -> 0.6f
        "Ok"       -> 0.4f
        else       -> 0.2f
    }

    // Open session — raised hand with no specific partner yet
    fun openSession() {
        viewModelScope.launch {
            val currentUser = _currentUser.value ?: return@launch
            try {
                val sessionId = UUID.randomUUID().toString()
                val session = HighFiveSession(
                    id = sessionId,
                    initiatorId = currentUser.id,
                    initiatorUsername = currentUser.username,
                    partnerId = null,
                    partnerUsername = "",
                    initiatorTimestamp = 0L,
                    lastUpdated = System.currentTimeMillis()
                )
                supabase.from("high_five_sessions").insert(session)
                supabase.from("users").update({
                    set("current_session", sessionId)
                }) { filter { eq("id", currentUser.id) } }
                _highFiveSession.value = session
                _highFiveState.value = HighFiveState.WaitingForPartner
                subscribeToSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "openSession failed", e)
                _error.value = "Failed to open session: ${e.message}"
            }
        }
    }

    fun readyToTap() {
        _highFiveState.value = HighFiveState.Idle
    }

    fun incrementTouchCount() { _touchCount.value++ }
    fun dismissNotification() { _inAppNotification.value = null }

    private suspend fun cleanupChannels() {
        try { sessionChannel?.unsubscribe() } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        onExitHighFiveScreen()
    }
}

sealed class HighFiveState {
    object Idle : HighFiveState()
    object Waiting : HighFiveState()
    object WaitingForPartner : HighFiveState()
    data class Success(val quality: Float) : HighFiveState()
    data class Error(val message: String) : HighFiveState()
}
