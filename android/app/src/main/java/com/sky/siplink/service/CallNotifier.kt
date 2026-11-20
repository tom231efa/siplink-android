package com.sky.siplink.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.sky.siplink.CallActivity
import com.sky.siplink.MainActivity
import com.sky.siplink.R
import com.sky.siplink.core.AppLanguage
import com.sky.siplink.core.logic.CallRules
import com.sky.siplink.core.model.CallInfo
import com.sky.siplink.core.model.Phrase
import com.sky.siplink.core.render

/** Builds and posts every notification of the app, in the app's language ([AppLanguage]). */
class CallNotifier(private val context: Context, private val language: AppLanguage) {
    private val manager = NotificationManagerCompat.from(context)

    private fun text(phrase: Phrase): String = language.localized().render(phrase)

    private fun text(@StringRes id: Int): String = language.localized().getString(id)

    /** Whether the incoming call notification is up (the app was not visible when the call came in). */
    private var incomingShown = false

    init {
        createChannels()
    }

    fun keepAlive(registered: Int, enabled: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(text(R.string.notification_running))
            .setContentText(
                if (enabled == 0) {
                    text(R.string.notification_no_accounts)
                } else {
                    text(Phrase.Count(R.plurals.notification_registered, enabled, listOf(registered, enabled)))
                },
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
            .setContentText(if (info.connectedAt == null) text(R.string.notification_calling) else info.remoteAddress)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(info), hangUp(info)))
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

    /** Hangs up; a room with more than one member is ended on the call screen, which asks first (CallRules). */
    private fun hangUp(info: CallInfo): PendingIntent {
        val room = info.room ?: return broadcast(ACTION_HANG_UP)
        return if (CallRules.confirmRoomEnd(room.present)) {
            activity(CallActivity::class.java, CallActivity.ACTION_END_ROOM, REQUEST_END_ROOM)
        } else {
            broadcast(ACTION_HANG_UP)
        }
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
            .setContentText(text(Phrase.of(R.string.notification_incoming_text, Phrase.of(incomingLabel(info)), info.remoteAddress)))
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

    @StringRes
    private fun incomingLabel(info: CallInfo): Int = when {
        info.intercom -> R.string.notification_intercom
        info.videoOffer -> R.string.notification_video_call
        else -> R.string.notification_incoming
    }

    fun cancelIncomingCall() {
        incomingShown = false
        manager.cancel(ID_INCOMING)
    }

    fun showMissedCall(name: String, address: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(text(R.string.notification_missed))
            .setContentText(if (name == address || address.isEmpty()) name else text(Phrase.of(R.string.notification_missed_text, name, address)))
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

    /** The language of the app changed: the channels are named in it. */
    fun onLanguageChanged() = createChannels()

    /** Creates the channels, or names them anew: the system keeps everything of a channel but its name and description. */
    private fun createChannels() {
        val channels = listOf(
            NotificationChannelCompat.Builder(CHANNEL_SERVICE, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(text(R.string.notification_channel_service_name))
                .setDescription(text(R.string.notification_channel_service_desc))
                .setShowBadge(false)
                .build(),
            // Ringtone and vibration are played by the SIP core itself, so the channel stays silent.
            NotificationChannelCompat.Builder(CHANNEL_INCOMING, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(text(R.string.notification_channel_incoming_name))
                .setDescription(text(R.string.notification_channel_incoming_desc))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_CALL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(text(R.string.notification_channel_call_name))
                .setDescription(text(R.string.notification_channel_call_desc))
                .setShowBadge(false)
                .build(),
            NotificationChannelCompat.Builder(CHANNEL_MISSED, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(text(R.string.notification_channel_missed_name))
                .setDescription(text(R.string.notification_channel_missed_desc))
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
        private const val REQUEST_END_ROOM = 4
    }
}
