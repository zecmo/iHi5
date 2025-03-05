package com.zecmo.internethighfive

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class InternetHighFiveApp : Application() {
    companion object {
        private const val TAG = "InternetHighFiveApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        try {
            // Initialize Firebase
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d(TAG, "Firebase App initialized successfully")
            }
            
            // Configure Firebase Database
            val database = FirebaseDatabase.getInstance("https://internethighfive-zecmo-default-rtdb.firebaseio.com")
            database.apply {
                try {
                    // Enable disk persistence
                    setPersistenceEnabled(true)
                    Log.d(TAG, "Firebase persistence enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable persistence: ${e.message}")
                }

                // Enable debug logging
                setLogLevel(com.google.firebase.database.Logger.Level.DEBUG)
                Log.d(TAG, "Firebase debug logging enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }
    }
} 