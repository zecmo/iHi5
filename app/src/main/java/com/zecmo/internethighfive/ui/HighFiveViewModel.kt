package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zecmo.internethighfive.SupabaseClient
import com.zecmo.internethighfive.data.HighFiveSession
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the entire high-five session lifecycle for one screen instance.
 *
 * Design principle (after repeated desync bugs): the session ROW is the single
 * source of truth. Everything the UI shows is derived from [_session]. There is
 * exactly ONE place that mutates session-derived state ([applySession]) and ONE
 * realtime subscription. No imperative "both connected" event counter — both
 * devices independently derive [partnerPresent] from the same row, so they can
 * never disagree about whether the partner has joined.
 */
class HighFiveViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "HighFiveVM"
        const val MAX_HIGH_FIVE_DIFF_MS = 3_000L     // grace window for a "synced" slap
        private const val HIGH_FIVE_TIMEOUT_MS = 8_000L  // give up waiting for partner tap
        private const val JOIN_RETRY_ATTEMPTS = 8        // initiator's row may not exist yet
        private const val JOIN_RETRY_DELAY_MS = 750L
        private const val POLL_INTERVAL_MS = 1_200L      // realtime fallback while a session is live
        private const val TOO_SLOW = "TooSlow"           // quality sentinel for a missed sync
    }

    private val supabase = SupabaseClient.client
    private val userPreferences = UserPreferences(application)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    /** The single source of truth. Everything else is derived from this. */
    private val _session = MutableStateFlow<HighFiveSession?>(null)
    val highFiveSession: StateFlow<HighFiveSession?> = _session.asStateFlow()

    /** Derived: true once a partner has joined the session. Both devices agree. */
    val partnerPresent: StateFlow<Boolean> = _session
        .map { !it?.partnerId.isNullOrEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _highFiveState = MutableStateFlow<HighFiveState>(HighFiveState.Idle)
    val highFiveState: StateFlow<HighFiveState> = _highFiveState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _partnerStats = MutableStateFlow<PartnerStats?>(null)
    val partnerStats: StateFlow<PartnerStats?> = _partnerStats.asStateFlow()

    private var sessionChannel: RealtimeChannel? = null
    private var pollingJob: Job? = null
    private var scored = false     // score the session exactly once
    private var hasLeft = false   // makes leave() idempotent across onDispose + onCleared

    init {
        viewModelScope.launch {
            userPreferences.userFlow.collect { credentials ->
                if (credentials != null && _currentUser.value?.id != credentials.id) {
                    _currentUser.value = supabase.from("users")
                        .select { filter { eq("id", credentials.id) } }
                        .decodeSingleOrNull<User>()
                }
            }
        }
    }

    // ── Single state-derivation point ──────────────────────────────────────────

    /**
     * The ONLY place that publishes a new session and derives terminal state from
     * it. Called after create, after join, and on every realtime update.
     */
    private fun applySession(session: HighFiveSession) {
        _session.value = session

        // Terminal: the initiator wrote a result — both devices land here via realtime.
        if (session.completed) {
            _highFiveState.value = if (session.quality == TOO_SLOW) {
                HighFiveState.Error("Too slow! Try again!")
            } else {
                HighFiveState.Success(qualityLabelToFloat(session.quality))
            }
            return
        }

        // Both taps are in but no result yet — score it exactly once (realtime + the
        // polling fallback can both deliver this row, so the `scored` guard matters).
        if (session.initiatorTimestamp > 0L && session.partnerTimestamp > 0L && !scored) {
            scored = true
            viewModelScope.launch { scoreSession(session) }
        }
    }

    // ── Screen lifecycle ───────────────────────────────────────────────────────

    fun onEnterHighFiveScreen() {
        hasLeft = false
        _highFiveState.value = HighFiveState.Idle
        viewModelScope.launch {
            val userId = _currentUser.value?.id ?: return@launch
            try {
                supabase.from("users").update({
                    set("hand_raised", true)
                    set("raised_hand_at", System.currentTimeMillis())
                }) { filter { eq("id", userId) } }
            } catch (e: Exception) {
                Log.e(TAG, "onEnterHighFiveScreen failed", e)
            }
        }
    }

    fun onExitHighFiveScreen() {
        if (hasLeft) return
        hasLeft = true
        pollingJob?.cancel()
        val currentUser = _currentUser.value ?: return
        val session = _session.value
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    supabase.from("users").update({
                        set("hand_raised", false)
                        set("current_session", "")
                    }) { filter { eq("id", currentUser.id) } }

                    // Only the initiator, and only an unfinished session, gets torn down —
                    // a completed row is kept for stats.
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
                try { sessionChannel?.unsubscribe() } catch (_: Exception) {}
            }
            _session.value = null
            _highFiveState.value = HighFiveState.Idle
        }
    }

    // ── Start a session (initiator) ────────────────────────────────────────────

    /** Open a session — raised hand. If [invitePartnerId] is set, ping that friend. */
    fun openSession(message: String = "", invitePartnerId: String? = null, inviteReceiverName: String? = null) {
        if (_session.value != null) return
        hasLeft = false; scored = false
        viewModelScope.launch {
            val currentUser = _currentUser.value ?: return@launch
            try {
                val sessionId = UUID.randomUUID().toString()
                val session = HighFiveSession(
                    id = sessionId,
                    initiatorId = currentUser.id,
                    initiatorUsername = currentUser.username,
                    partnerId = null,
                    lastUpdated = System.currentTimeMillis(),
                    message = message
                )
                supabase.from("high_five_sessions").insert(session)
                supabase.from("users").update({
                    set("current_session", sessionId)
                }) { filter { eq("id", currentUser.id) } }
                applySession(session)
                _highFiveState.value = HighFiveState.WaitingForPartner
                subscribeToSession(sessionId)
                // Notify only AFTER the row exists so the receiver's join finds it.
                if (invitePartnerId != null) {
                    notifyUser(invitePartnerId, "invite", message, inviteReceiverName ?: "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "openSession failed", e)
                _error.value = "Failed to open session: ${e.message}"
            }
        }
    }

    // ── Join a session (partner) ───────────────────────────────────────────────

    fun connectToUser(partnerId: String) {
        if (_session.value != null) return
        hasLeft = false; scored = false
        _highFiveState.value = HighFiveState.Idle
        viewModelScope.launch {
            val currentUser = _currentUser.value ?: return@launch
            Log.d(TAG, "connectToUser me=${currentUser.id} initiator=$partnerId")
            try {
                // The initiator's openSession() may not have hit the DB yet (the generic
                // notification is fired before the session row is created), so retry.
                var target: HighFiveSession? = null
                repeat(JOIN_RETRY_ATTEMPTS) { attempt ->
                    if (target != null) return@repeat
                    val candidates = supabase.from("high_five_sessions")
                        .select {
                            filter {
                                eq("initiator_id", partnerId)
                                eq("completed", false)
                            }
                        }
                        .decodeList<HighFiveSession>()
                    // A fresh open session, OR one we already joined (idempotent re-entry
                    // after a duplicate notification tap / screen recreation).
                    target = candidates.firstOrNull {
                        it.partnerId.isNullOrEmpty() || it.partnerId == currentUser.id
                    }
                    if (target == null && attempt < JOIN_RETRY_ATTEMPTS - 1) delay(JOIN_RETRY_DELAY_MS)
                }

                val session = target
                if (session == null) {
                    Log.w(TAG, "connectToUser: no open session for $partnerId")
                    _highFiveState.value =
                        HighFiveState.Error("Partner isn't ready — make sure they raised their hand first")
                    return@launch
                }

                // Claim the session if we haven't already.
                if (session.partnerId != currentUser.id) {
                    supabase.from("high_five_sessions").update({
                        set("partner_id", currentUser.id)
                        set("partner_username", currentUser.username)
                        set("last_updated", System.currentTimeMillis())
                    }) { filter { eq("id", session.id) } }
                }
                supabase.from("users").update({
                    set("current_session", session.id)
                }) { filter { eq("id", currentUser.id) } }

                // Subscribe BEFORE the final read so we can't miss an update in between,
                // then publish the authoritative row (partner_id populated).
                subscribeToSession(session.id)
                val joined = supabase.from("high_five_sessions")
                    .select { filter { eq("id", session.id) } }
                    .decodeSingleOrNull<HighFiveSession>()
                    ?: session.copy(partnerId = currentUser.id, partnerUsername = currentUser.username)
                applySession(joined)
            } catch (e: Exception) {
                Log.e(TAG, "connectToUser failed", e)
                _error.value = "Error connecting: ${e.message}"
            }
        }
    }

    // ── Realtime ───────────────────────────────────────────────────────────────

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
                }.onEach { _ ->
                    supabase.from("high_five_sessions")
                        .select { filter { eq("id", sessionId) } }
                        .decodeSingleOrNull<HighFiveSession>()
                        ?.let { applySession(it) }
                }.launchIn(viewModelScope)
                channel.subscribe()
                sessionChannel = channel

                // Catch-up: the partner may have joined before our socket was live.
                supabase.from("high_five_sessions")
                    .select { filter { eq("id", sessionId) } }
                    .decodeSingleOrNull<HighFiveSession>()
                    ?.let { applySession(it) }

                startSessionPolling(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "subscribeToSession failed", e)
            }
        }
    }

    /**
     * Fallback for dropped/laggy realtime: while the session is live, re-read the row
     * on a short interval and feed it through [applySession]. This is what guarantees
     * the initiator sees the partner join (and both see the result) even when the
     * single realtime event never arrives — the cause of the "sender never counts
     * down" hangs. Idempotent: [applySession] only acts on meaningful transitions.
     */
    private fun startSessionPolling(sessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (!hasLeft) {
                delay(POLL_INTERVAL_MS)
                if (hasLeft) break
                val current = _session.value
                if (current != null && current.completed) break
                try {
                    supabase.from("high_five_sessions")
                        .select { filter { eq("id", sessionId) } }
                        .decodeSingleOrNull<HighFiveSession>()
                        ?.let { applySession(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "session poll failed", e)
                }
            }
        }
    }

    // ── Tap mechanics ──────────────────────────────────────────────────────────

    fun initiateHighFive() {
        if (_highFiveState.value != HighFiveState.Idle) return
        val currentUser = _currentUser.value ?: return
        val session = _session.value ?: return

        viewModelScope.launch {
            _highFiveState.value = HighFiveState.Waiting
            val isInitiator = currentUser.id == session.initiatorId
            try {
                // Server clock via RPC — removes device clock skew from the scoring.
                supabase.postgrest.rpc(
                    "record_tap",
                    buildJsonObject {
                        put("session_id", session.id)
                        put("is_initiator", isInitiator)
                    }
                )
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
        val tooSlow = timeDiff > MAX_HIGH_FIVE_DIFF_MS
        val quality = calculateQuality(timeDiff)
        Log.d(TAG, "scoreSession diff=${timeDiff}ms tooSlow=$tooSlow")

        // Both devices set their local terminal state identically from the same data.
        _highFiveState.value =
            if (tooSlow) HighFiveState.Error("Too slow! Try again!")
            else HighFiveState.Success(quality)

        // Only the initiator persists the result — partner learns via realtime. We
        // persist even on too-slow (sentinel) so a device that missed the tap update
        // still gets a terminal `completed` event instead of hanging.
        if (_currentUser.value?.id == session.initiatorId) {
            try {
                supabase.from("high_five_sessions").update({
                    set("completed", true)
                    set("quality", if (tooSlow) TOO_SLOW else qualityLabel(quality))
                    set("last_updated", System.currentTimeMillis())
                }) { filter { eq("id", session.id) } }
            } catch (e: Exception) {
                Log.e(TAG, "scoreSession persist failed", e)
            }
        }
    }

    fun readyToTap() {
        if (_highFiveState.value is HighFiveState.Success || _highFiveState.value is HighFiveState.Error) return
        _highFiveState.value = HighFiveState.Idle
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

    // ── Notifications ──────────────────────────────────────────────────────────

    private suspend fun notifyUser(recipientId: String, type: String, message: String, receiverName: String) {
        val sender = _currentUser.value ?: return
        try {
            supabase.functions.invoke(
                function = "send-notification",
                body = buildJsonObject {
                    put("type", type)
                    put("recipientIds", buildJsonArray { add(recipientId) })
                    put("senderName", sender.username)
                    put("senderId", sender.id)
                    put("message", message)
                    put("receiverName", receiverName)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "notifyUser failed", e)
        }
    }

    // ── Stats ──────────────────────────────────────────────────────────────────

    fun loadPartnerStats() {
        val myId = _currentUser.value?.id ?: return
        val session = _session.value ?: return
        val partnerId = if (myId == session.initiatorId) session.partnerId ?: return else session.initiatorId
        _partnerStats.value = null
        viewModelScope.launch {
            try {
                val asInitiator = supabase.from("high_five_sessions")
                    .select { filter { eq("initiator_id", myId); eq("partner_id", partnerId); eq("completed", true) } }
                    .decodeList<HighFiveSession>()
                val asPartner = supabase.from("high_five_sessions")
                    .select { filter { eq("initiator_id", partnerId); eq("partner_id", myId); eq("completed", true) } }
                    .decodeList<HighFiveSession>()
                val all = (asInitiator + asPartner).filter { it.quality != TOO_SLOW }
                val breakdown = all.groupBy { it.quality }.mapValues { it.value.size }
                val avgQuality = if (all.isEmpty()) 0f
                    else all.sumOf { qualityLabelToFloat(it.quality).toDouble() }.toFloat() / all.size
                val (label, emoji) = deriveFlavorLabel(all.size, avgQuality)
                _partnerStats.value = PartnerStats(all.size, breakdown, label, emoji)
            } catch (e: Exception) {
                Log.e(TAG, "loadPartnerStats failed", e)
            }
        }
    }

    private fun deriveFlavorLabel(count: Int, avgQuality: Float): Pair<String, String> = when {
        count == 1                              -> "First High Five!"       to "🎉"
        count <= 3  && avgQuality < 0.5f        -> "Still Figuring It Out"  to "🤔"
        count <= 3                              -> "Getting Acquainted"     to "👋"
        count <= 10 && avgQuality >= 0.8f       -> "Natural Chemistry"      to "⚡"
        count <= 10 && avgQuality >= 0.5f       -> "Slap Happy"             to "😄"
        count <= 10                             -> "Casual Clappers"        to "🖐️"
        count <= 25 && avgQuality >= 0.8f       -> "Synchrony Seekers"      to "🎯"
        count <= 25                             -> "Hi-Five Regulars"       to "🔄"
        count <= 50 && avgQuality >= 0.9f       -> "Timing Twins"           to "⏱️"
        count <= 50                             -> "Dedicated Slappers"     to "👏"
        avgQuality >= 0.9f                      -> "Palm Psychics"          to "🔮"
        else                                    -> "Hi-Five Legends"        to "🏆"
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

data class PartnerStats(
    val totalCount: Int,
    val qualityBreakdown: Map<String, Int>,
    val flavorLabel: String,
    val flavorEmoji: String
)
