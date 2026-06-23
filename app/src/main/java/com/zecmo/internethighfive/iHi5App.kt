package com.zecmo.internethighfive

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.zecmo.internethighfive.notification.NotificationHelper
import com.zecmo.internethighfive.notifications.FirebaseMessagingService

class iHi5App : Application() {
    companion object {
        private const val TAG = "iHi5App"
    }

    override fun onCreate() {
        super.onCreate()

        // Firebase — kept only for Cloud Messaging (push notifications)
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            Log.d(TAG, "Firebase initialized (FCM only)")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase init failed", e)
        }

        // Create notification channels at startup so they exist before any FCM message arrives
        NotificationHelper.createNotificationChannel(this)
        FirebaseMessagingService.createChannelStatic(this)

        // Supabase client is initialized lazily via SupabaseClient singleton
        Log.d(TAG, "iHi5App started")
    }
}
