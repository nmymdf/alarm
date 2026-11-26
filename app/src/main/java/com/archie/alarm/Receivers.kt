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
import com.archie.alarm.quiet.Quiet

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
        // 已刪除或找不到的鬧鐘：把它在系統裡殘留的響鈴全部清掉，什麼都不做
        if (Store.get(id) == null) {
            Scheduler.cancel(ctx, id)
            Advance.clear(ctx, id)
            return
        }
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
        val at = intent.getLongExtra("at", 0L)
        // 同一筆、同一個時間的響鈴，2 分鐘內只響一次（避免重複響）
        val key = "$id:${if (snooze) "s" else "m"}"
        val p = ctx.getSharedPreferences("fired", Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()
        val lastAt = p.getLong("$key:at", -1L)
        val lastMs = p.getLong("$key:ms", 0L)
        if (nowMs - lastMs < 120_000L && (snooze || lastAt == at)) return
        p.edit().putLong("$key:at", at).putLong("$key:ms", nowMs).commit()
        Advance.clear(ctx, id)
        if (!snooze) {
            if (item.mask == 0 && item.monthDay == 0) {
                Store.upsert(item.copy(enabled = false))
            } else {
                // 從這次響鈴時間之後再找下一次，就算系統提早幾毫秒送來也不會再排到同一時間
                val base = maxOf(at, nowMs) + 60_000L
                Scheduler.schedule(
                    ctx, item,
                    java.time.Instant.ofEpochMilli(base).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                )
            }
        }
        // 一鍵安靜開著：不響、不震、不亮螢幕，只留一則沒有聲音的通知（下一次照常排好了）
        if (Quiet.isOn(ctx)) {
            Quiet.recordSkippedAlarm(ctx, id, item.timeText(), item.label)
            return
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
        if (Quiet.isOn(ctx)) {
            Quiet.recordSkippedAlarm(ctx, item.id, item.timeText(), item.label)
            return
        }
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
