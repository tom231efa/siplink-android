package com.sky.siplink.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.sky.siplink.CallActivity
import com.sky.siplink.MainActivity
import com.sky.siplink.R
import com.sky.siplink.core.model.CallInfo

/** Builds and posts every notification of the app. */
class CallNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Whether the incoming call notification is up (the app was not visible when the call came in). */
    private var incomingShown = false

    init {
        createChannels()
    }

    fun keepAlive(registered: Int, enabled: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("SipLink 正在后台保持在线")
            .setContentText(
                if (enabled == 0) "没有启用的 SIP 账号" else "$registered / $enabled 个账号已注册",
            )
            .setContentIntent(activity(MainActivity::class.java, null, REQUEST_MAIN))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    fun ongoingCall(info: CallInfo): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(info.remoteName)
            .setContentText(if (info.connectedAt == null) "正在呼叫…" else info.remoteAddress)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(info), broadcast(ACTION_HANG_UP)))
            .setContentIntent(activity(CallActivity::class.java, null, REQUEST_CALL))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
        info.connectedAt?.let {
            builder.setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - it))
        }
        return builder.build()
    }

    /** Renames the incoming call notification if there is one; it does not ring or pop up again. */
    fun updateIncomingCall(info: CallInfo) {
        if (incomingShown) showIncomingCall(info)
    }

    fun showIncomingCall(info: CallInfo) {
        incomingShown = true
        val openCall = activity(CallActivity::class.java, null, REQUEST_CALL)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(info.remoteName)
            .setContentText(if (info.intercom) "对讲呼叫 · ${info.remoteAddress}" else "来电 · ${info.remoteAddress}")
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(info),
                    broadcast(ACTION_DECLINE),
                    // Answering goes through the call screen so the app is visible and may use the microphone.
                    activity(CallActivity::class.java, CallActivity.ACTION_ANSWER, REQUEST_ANSWER),
                ),
            )
            .setContentIntent(openCall)
            .setFullScreenIntent(openCall, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        post(ID_INCOMING, notification)
    }

    fun cancelIncomingCall() {
        incomingShown = false
        manager.cancel(ID_INCOMING)
    }

    fun showMissedCall(name: String, address: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("未接来电")
            .setContentText(if (name == address || address.isEmpty()) name else "$name（$address）")
            .setContentIntent(activity(MainActivity::class.java, null, REQUEST_MAIN))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .build()
        post(ID_MISSED_BASE + (address.hashCode() and 0xFFFF), notification)
    }

    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    private fun person(info: CallInfo): Person =
        Person.Builder().setName(info.remoteName).setImportant(true).build()

    private fun activity(target: Class<*>, action: String?, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, target).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun broadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannels() {
        val channels = listOf(
            NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_service_name))
                .setDescription(context.getString(R.string.channel_service_desc))
                .setShowBadge(false)
                .build(),
            // Ringtone and vibration are played by the SIP core itself, so the channel stays silent.
            NotificationChannelCompat.Builder(CHANNEL_INCOMING, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_incoming_name))
                .setDescription(context.getString(R.string.channel_incoming_desc))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_CALL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_call_name))
                .setDescription(context.getString(R.string.channel_call_desc))
                .setShowBadge(false)
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_MISSED, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_missed_name))
                .setDescription(context.getString(R.string.channel_missed_desc))
                .build(),
        )
        manager.createNotificationChannelsCompat(channels)
    }

    companion object {
        const val ID_SERVICE = 1
        private const val ID_INCOMING = 2
        private const val ID_MISSED_BASE = 0x10000

        const val ACTION_DECLINE = "com.sky.siplink.action.DECLINE"
        const val ACTION_HANG_UP = "com.sky.siplink.action.HANG_UP"

        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_INCOMING = "incoming_call"
        private const val CHANNEL_CALL = "ongoing_call"
        private const val CHANNEL_MISSED = "missed_call"

        private const val REQUEST_MAIN = 1
        private const val REQUEST_CALL = 2
        private const val REQUEST_ANSWER = 3
    }
}
