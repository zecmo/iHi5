package com.zecmo.internethighfive.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {
    companion object {
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val EMAIL_KEY = stringPreferencesKey("email")
    }

    val userFlow: Flow<User?> = context.dataStore.data.map { preferences ->
        val id = preferences[USER_ID_KEY]
        val username = preferences[USERNAME_KEY]
        val email = preferences[EMAIL_KEY]

        if (id != null && username != null) {
            User(
                id = id,
                username = username,
                email = email ?: ""
            )
        } else {
            null
        }
    }

    suspend fun saveUser(id: String, username: String, email: String = "") {
        context.dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = id
            preferences[USERNAME_KEY] = username
            preferences[EMAIL_KEY] = email
        }
    }

    suspend fun getUser(): User? {
        val id = context.dataStore.data.first()[USER_ID_KEY] ?: return null
        val username = context.dataStore.data.first()[USERNAME_KEY] ?: return null
        val email = context.dataStore.data.first()[EMAIL_KEY] ?: ""

        return User(
            id = id,
            username = username,
            email = email
        )
    }

    suspend fun clearUser() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}

data class UserCredentials(
    val id: String,
    val username: String
) 