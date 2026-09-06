// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

package io.github.vsima.canton.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.vsima.canton.dapp.wallet.DappActivity

/**
 * Local notifications for the sheetless agent outcomes. A policy that
 * refuses or auto-approves without a sheet is only honest if the phone
 * still tells its owner at the moment it happens; the Activity tab is the
 * durable record, this is the tap on the shoulder.
 */
object AgentNotifications {
    private const val CHANNEL_ID = "agent-activity"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent activity",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Payments your connected agents made or tried without an approval sheet"
            },
        )
    }

    fun notify(context: Context, activity: DappActivity) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val amount = activity.transfer?.let { "${it.amount} ${if (it.instrumentId == "Amulet") "CC" else it.instrumentId}" }
        val (title, body) = when (activity.kind) {
            DappActivity.Kind.TRANSACTION_AUTO_APPROVED ->
                "${activity.peerName} paid ${amount ?: "a transfer"}" to
                    "Auto-approved by your spend policy. Tap Activity for details."
            DappActivity.Kind.TRANSACTION_REFUSED ->
                "${activity.peerName} was refused" to
                    (activity.detail ?: "Outside your spend policy.")
            DappActivity.Kind.TRANSACTION_RATE_LIMITED ->
                "${activity.peerName} was rate-limited" to
                    (activity.detail ?: "Too many requests.")
            else -> return
        }
        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(activity.at.toEpochMilli().toInt(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; the
            // Activity tab still carries the record.
        }
    }
}
