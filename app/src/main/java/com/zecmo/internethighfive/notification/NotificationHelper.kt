package com.zecmo.internethighfive.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zecmo.internethighfive.MainActivity
import com.zecmo.internethighfive.R

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    // A channel's sound is locked in at creation time and cannot be changed afterwards
    // (recreating the same id restores the old settings). To ship the custom sound we use
    // a fresh channel id; the user's own settings on this channel are never overwritten.
    const val CHANNEL_ID = "high_five_requests_v2"
    private const val CHANNEL_NAME = "High Five Requests"
    private const val CHANNEL_DESCRIPTION = "Notifications for incoming high five requests"

    // Legacy channels we no longer post to. Removed so users don't see stale/duplicate entries.
    private val LEGACY_CHANNEL_IDS = listOf("high_five_requests", "high_five_channel")

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // createNotificationChannel is idempotent: if the channel already exists this is a
            // no-op and any customizations the user made (silenced it, changed importance) are kept.
            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.notification_hifi}")
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESCRIPTION
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)

            // Clean up channels from earlier versions that nothing posts to anymore.
            LEGACY_CHANNEL_IDS.forEach { notificationManager.deleteNotificationChannel(it) }
        }
    }

    fun showHighFiveRequest(
        context: Context,
        senderId: String,
        senderName: String,
        notificationId: Int
    ) {
        // Check for notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission not granted")
            return
        }

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("sender_id", senderId)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("High Five Request!")
                .setContentText("$senderName wants to high five with you!")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
} 