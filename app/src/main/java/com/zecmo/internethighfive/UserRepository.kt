package com.zecmo.internethighfive

import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository {
    private val auth = Firebase.auth
    private val database = FirebaseDatabase.getInstance().reference
    private val usersRef = database.child("users")

    suspend fun getCurrentUserId(): String = withContext(Dispatchers.IO) {
        auth.currentUser?.uid ?: throw IllegalStateException("No authenticated user")
    }

    suspend fun getCurrentUser(): User? = withContext(Dispatchers.IO) {
        val userId = getCurrentUserId()
        usersRef.child(userId).get().await().getValue(User::class.java)
    }

    suspend fun getUser(userId: String): User? = withContext(Dispatchers.IO) {
        usersRef.child(userId).get().await().getValue(User::class.java)
    }
} 