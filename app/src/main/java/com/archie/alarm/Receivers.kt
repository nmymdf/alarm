package com.archie.alarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Pending.init(this)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Pending.init(ctx)
        val id = intent.getLongExtra("id", -1L)
        when (intent.getIntExtra("kind", Scheduler.MAIN)) {
            Scheduler.ACK -> {
                Advance.clear(ctx, id)
                return
            }
            Scheduler.NAG -> {
                Advance.nag(ctx, id)
                return
            }
            Scheduler.ADV -> {
                val item = Store.get(id) ?: return
                if (item.enabled) Advance.fire(ctx, item, intent.getLongExtra("at", 0L))
                return
            }
        }

        // 準時響鈴（含稍後提醒再響）
        val snooze = intent.getIntExtra("kind", Scheduler.MAIN) == Scheduler.SNOOZE
        val item = Store.get(id) ?: return
        Advance.clear(ctx, id)
        if (!snooze) {
            if (item.mask == 0) {
                Store.upsert(item.copy(enabled = false))
            } else {
                Scheduler.schedule(ctx, item)
            }
        }
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RingService::class.java).putExtra("id", id)
            )
        } catch (e: Exception) {
            Fallback.post(ctx, item, "無法啟動響鈴服務：${e.javaClass.simpleName}")
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Pending.init(ctx)
        Scheduler.scheduleAll(ctx)
        Advance.restore(ctx)
    }
}

/** 主要響鈴失敗時的備援：直接用系統通知（鬧鐘音效＋震動）提醒 */
object Fallback {
    fun post(ctx: Context, item: Item, why: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = "ring_fallback"
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            nm.createNotificationChannel(
                NotificationChannel(ch, "鬧鐘（備援）", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        sound,
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("${item.timeText()} ${item.label.ifBlank { "鬧鐘" }}")
                .setContentText(why)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .build()
            nm.notify(2000 + item.id.toInt(), n)
        } catch (_: Exception) {
        }
    }
}
