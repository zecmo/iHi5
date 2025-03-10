package com.zecmo.internethighfive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HighFiveViewModel : ViewModel() {
    companion object {
        private const val TAG = "HighFiveViewModel"
    }

    private val _currentSession = MutableStateFlow<Session?>(null)
    val highFiveSession: StateFlow<Session?> = _currentSession

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _partnerUsername = MutableStateFlow<String?>(null)
    val partnerUsername: StateFlow<String?> = _partnerUsername

    private val _touchCount = MutableStateFlow(0)
    val touchCount: StateFlow<Int> = _touchCount

    private val _highFiveState = MutableStateFlow<HighFiveState>(HighFiveState.Initial)
    val highFiveState: StateFlow<HighFiveState> = _highFiveState

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser

    private val sessionRepository: SessionRepository = SessionRepository()
    private val userRepository: UserRepository = UserRepository()
    private var userId: String? = null

    init {
        viewModelScope.launch {
            try {
                userId = userRepository.getCurrentUserId()
                _currentUser.value = userRepository.getCurrentUser()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing ViewModel", e)
            }
        }
    }

    fun incrementTouchCount() {
        _touchCount.value = _touchCount.value + 1
    }

    fun initiateHighFive() {
        viewModelScope.launch {
            try {
                _currentSession.value?.let { session ->
                    sessionRepository.updateSessionTouchCount(session.id, _touchCount.value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating high five", e)
            }
        }
    }

    fun createHighFiveSession(partnerId: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.createSession(partnerId)
                handleSessionUpdate(session)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating session", e)
            }
        }
    }

    fun connectToUser(partnerId: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.connectToSession(partnerId)
                handleSessionUpdate(session)
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to session", e)
            }
        }
    }

    private fun handleSessionUpdate(session: Session?) {
        _currentSession.value = session
        if (session == null) {
            _isConnected.value = false
            _partnerUsername.value = null
            _touchCount.value = 0
            return
        }
        
        _isConnected.value = true
        _partnerUsername.value = session.partnerUsername
        
        // If the initiator has left, clean up the session
        if (session.initiatorId == userId && !session.isInitiatorActive) {
            deleteSession()
            return
        }
        
        // If the partner has left, clean up the session
        if (session.partnerId == userId && !session.isPartnerActive) {
            deleteSession()
            return
        }
    }

    private fun deleteSession() {
        viewModelScope.launch {
            try {
                _currentSession.value?.let { session ->
                    sessionRepository.deleteSession(session.id)
                }
                _currentSession.value = null
                _isConnected.value = false
                _partnerUsername.value = null
                _touchCount.value = 0
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting session", e)
            }
        }
    }

    fun onEnterHighFiveScreen() {
        _highFiveState.value = HighFiveState.Initial
        _touchCount.value = 0
    }

    fun onExitHighFiveScreen() {
        viewModelScope.launch {
            try {
                _currentSession.value?.let { session ->
                    sessionRepository.leaveSession(session.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error leaving session", e)
            }
        }
    }
}

sealed class HighFiveState {
    object Initial : HighFiveState()
    object InProgress : HighFiveState()
    object Success : HighFiveState()
    data class Error(val message: String) : HighFiveState()
}

data class Session(
    val id: String,
    val initiatorId: String,
    val partnerId: String,
    val partnerUsername: String,
    val isInitiatorActive: Boolean,
    val isPartnerActive: Boolean,
    val touchCount: Int = 0
)

data class User(
    val id: String,
    val username: String
) 