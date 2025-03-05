package com.zecmo.internethighfive.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zecmo.internethighfive.MainActivity
import com.zecmo.internethighfive.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

class FirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "FCMService"
        private const val CHANNEL_ID = "high_five_channel"
        private const val CHANNEL_NAME = "High Fives"
        private const val NOTIFICATION_ID = 1
        private val USER_ID_KEY = stringPreferencesKey("user_id")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCM Service created")
        checkPlayServices()
        createNotificationChannel()
    }

    private fun checkPlayServices() {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        when (resultCode) {
            ConnectionResult.SUCCESS -> {
                Log.d(TAG, "Google Play Services is available")
            }
            else -> {
                Log.e(TAG, "Google Play Services is not available: ${googleApiAvailability.getErrorString(resultCode)}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Received FCM message: ${message.data}")
        Log.d(TAG, "Message priority: ${message.priority}")
        Log.d(TAG, "Message TTL: ${message.ttl}")
        
        message.data.let { data ->
            when (data["type"]) {
                "high_five_request" -> {
                    val senderName = data["senderName"] ?: "Someone"
                    Log.d(TAG, "Received high five request from: $senderName")
                    showNotification(
                        "New High Five Request!",
                        "$senderName wants to high five with you!"
                    )
                }
                "high_five_ready" -> {
                    val partnerName = data["partnerName"] ?: "Your partner"
                    Log.d(TAG, "Partner ready notification from: $partnerName")
                    showNotification(
                        "Ready to High Five!",
                        "$partnerName is ready to high five!"
                    )
                }
                "high_five_complete" -> {
                    val quality = data["quality"]?.toFloatOrNull()?.times(100) ?: 0f
                    Log.d(TAG, "High five completed with quality: $quality%")
                    showNotification(
                        "High Five Complete!",
                        "You just completed a high five with ${data["partnerName"]}! Quality: $quality%"
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown notification type: ${data["type"]}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Received new FCM token: $token")
        // Store the token in Firebase Database for the current user
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val preferences = dataStore.data.first()
                val userId = preferences[USER_ID_KEY]
                
                if (userId != null) {
                    val database = com.google.firebase.database.FirebaseDatabase.getInstance().reference
                    database.child("users").child(userId).child("fcmToken").setValue(token)
                        .addOnSuccessListener {
                            Log.d(TAG, "FCM token successfully stored in database")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to store FCM token in database", e)
                        }
                } else {
                    Log.w(TAG, "Cannot store FCM token: User ID not found in DataStore")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error storing FCM token", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for high five events"
                    enableVibration(true)
                }
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channel", e)
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        try {
            Log.d(TAG, "Showing notification - Title: $title, Message: $message")
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
} 