package com.zecmo.internethighfive

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SessionRepository {
    private val database = FirebaseDatabase.getInstance().reference
    private val sessionsRef = database.child("sessions")
    private val userRepository = UserRepository()

    suspend fun createSession(partnerId: String): Session = withContext(Dispatchers.IO) {
        val currentUser = userRepository.getCurrentUser() ?: throw IllegalStateException("No current user")
        val sessionId = sessionsRef.push().key ?: throw IllegalStateException("Could not generate session ID")
        
        val session = Session(
            id = sessionId,
            initiatorId = currentUser.id,
            partnerId = partnerId,
            partnerUsername = "",  // Will be updated when partner connects
            isInitiatorActive = true,
            isPartnerActive = false
        )

        suspendCancellableCoroutine { continuation ->
            sessionsRef.child(sessionId).setValue(session)
                .addOnSuccessListener {
                    continuation.resume(session)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    suspend fun connectToSession(partnerId: String): Session = withContext(Dispatchers.IO) {
        val currentUser = userRepository.getCurrentUser() ?: throw IllegalStateException("No current user")
        
        suspendCancellableCoroutine { continuation ->
            sessionsRef.orderByChild("partnerId").equalTo(currentUser.id)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val session = snapshot.children.firstOrNull()?.getValue(Session::class.java)
                        if (session != null) {
                            val updatedSession = session.copy(
                                isPartnerActive = true,
                                partnerUsername = currentUser.username
                            )
                            sessionsRef.child(session.id).setValue(updatedSession)
                                .addOnSuccessListener {
                                    continuation.resume(updatedSession)
                                }
                                .addOnFailureListener { e ->
                                    continuation.resumeWithException(e)
                                }
                        } else {
                            continuation.resumeWithException(IllegalStateException("Session not found"))
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        continuation.resumeWithException(error.toException())
                    }
                })
        }
    }

    suspend fun updateSessionTouchCount(sessionId: String, touchCount: Int) {
        withContext(Dispatchers.IO) {
            sessionsRef.child(sessionId).child("touchCount").setValue(touchCount).await()
        }
    }

    suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessionsRef.child(sessionId).removeValue().await()
        }
    }

    suspend fun leaveSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            val currentUser = userRepository.getCurrentUser() ?: return@withContext
            val sessionSnapshot = sessionsRef.child(sessionId).get().await()
            val session = sessionSnapshot.getValue(Session::class.java) ?: return@withContext

            when (currentUser.id) {
                session.initiatorId -> sessionsRef.child(sessionId).child("isInitiatorActive").setValue(false).await()
                session.partnerId -> sessionsRef.child(sessionId).child("isPartnerActive").setValue(false).await()
            }
        }
    }
} 