package com.zecmo.internethighfive.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zecmo.internethighfive.SupabaseClient
import com.zecmo.internethighfive.data.User
import com.zecmo.internethighfive.data.UserPreferences
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AddUserViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AddUserViewModel"
    }

    private val supabase = SupabaseClient.client
    private val userPreferences = UserPreferences(application)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSuccess = MutableStateFlow(false)
    val isSuccess: StateFlow<Boolean> = _isSuccess.asStateFlow()

    fun addFriendByUsername(username: String) {
        if (username.isBlank()) {
            _error.value = "Username cannot be empty"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _isSuccess.value = false
            try {
                val currentUserId = userPreferences.userFlow.first()?.id
                if (currentUserId == null) {
                    _error.value = "You must be logged in to add friends"
                    return@launch
                }

                val found = supabase.from("users")
                    .select { filter { eq("username", username) } }
                    .decodeSingleOrNull<User>()

                if (found == null) {
                    _error.value = "User '$username' not found"
                    return@launch
                }
                if (found.id == currentUserId) {
                    _error.value = "You can't add yourself"
                    return@launch
                }

                // Upsert friendship in both directions
                supabase.from("friendships").upsert(
                    buildJsonObject {
                        put("user_id", currentUserId)
                        put("friend_id", found.id)
                    }
                )
                supabase.from("friendships").upsert(
                    buildJsonObject {
                        put("user_id", found.id)
                        put("friend_id", currentUserId)
                    }
                )
                Log.d(TAG, "Added friend: ${found.username}")
                _isSuccess.value = true
            } catch (e: Exception) {
                Log.e(TAG, "addFriendByUsername failed", e)
                _error.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
