package com.archie.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 提前提醒：一般＝通知留著＋沒確認就再提醒；重要＝像鬧鐘一樣響 */
object Advance {
    private const val CH = "advance"
    private const val CH_QUIET = "advance_quiet"
    private const val NAG_EVERY_MS = 5 * 60_000L
    private const val MAX_COUNT = 4 // 第一次＋最多 3 次再提醒

    fun notifId(id: Long): Int = 3000 + (id.toInt() and 0xFFFF)

    fun fire(ctx: Context, item: Item, at: Long) {
        val eventMillis = if (at > 0) at else System.currentTimeMillis() + item.advanceMin * 60_000L
        if (item.advanceImportant) {
            try {
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, RingService::class.java)
                        .putExtra("id", item.id)
                        .putExtra("adv", true)
                        .putExtra("at", eventMillis)
                )
            } catch (e: Exception) {
                Fallback.post(ctx, item, "提前提醒：無法啟動響鈴（${e.javaClass.simpleName}）")
            }
        } else {
            Pending.put(PendingAdv(item.id, eventMillis, 1))
            post(ctx, item, eventMillis, quiet = false)
            scheduleNag(ctx, item.id, eventMillis, 1)
        }
    }

    fun nag(ctx: Context, id: Long) {
        val p = Pending.get(id) ?: return
        val item = Store.get(id)
        if (item == null || System.currentTimeMillis() >= p.eventMillis) {
            clear(ctx, id)
            return
        }
        val c = p.count + 1
        Pending.put(p.copy(count = c))
        post(ctx, item, p.eventMillis, quiet = false)
        scheduleNag(ctx, id, p.eventMillis, c)
    }

    private fun scheduleNag(ctx: Context, id: Long, eventMillis: Long, count: Int) {
        if (count >= MAX_COUNT) return
        val at = System.currentTimeMillis() + NAG_EVERY_MS
        if (at >= eventMillis) return // 準時那次快到了，不用再提醒
        Scheduler.setClock(ctx, id, at, Scheduler.NAG, 0L)
    }

    /** 按「知道了」、刪除、停用、改過內容或準時響鈴後：清掉待確認與通知 */
    fun clear(ctx: Context, id: Long) {
        Pending.remove(id)
        Scheduler.cancelKind(ctx, id, Scheduler.NAG)
        ctx.getSystemService(NotificationManager::class.java).cancel(notifId(id))
    }

    /** 開機或更新後：把還沒確認的提前提醒重新放回通知列（不響） */
    fun restore(ctx: Context) {
        val now = System.currentTimeMillis()
        Pending.list.value.forEach { p ->
            val item = Store.get(p.id)
            if (item == null || p.eventMillis <= now) {
                clear(ctx, p.id)
            } else {
                post(ctx, item, p.eventMillis, quiet = true)
                scheduleNag(ctx, p.id, p.eventMillis, p.count)
            }
        }
    }

    private fun post(ctx: Context, item: Item, eventMillis: Long, quiet: Boolean) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            nm.createNotificationChannel(
                NotificationChannel(CH, "提前提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 300, 500)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_QUIET, "提前提醒（靜音重貼）", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ack = Scheduler.pi(ctx, item.id, Scheduler.ACK, 0L)
            val title = "提前提醒・" + item.label.ifBlank { "鬧鐘" }
            val line = "${item.timeText()} 開始（${Fmt.untilMillis(System.currentTimeMillis(), eventMillis)}）"
            val big = if (item.note.isBlank()) line else line + "\n" + item.note
            val n = NotificationCompat.Builder(ctx, if (quiet) CH_QUIET else CH)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setContentIntent(open)
                .addAction(0, "知道了", ack)
                .build()
            nm.notify(notifId(item.id), n)
        } catch (_: Exception) {
        }
    }
}
