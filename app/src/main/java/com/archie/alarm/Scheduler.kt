package com.archie.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

object Scheduler {
    const val MAIN = 0
    const val SNOOZE = 1
    const val ADV = 2
    const val NAG = 3
    const val ACK = 4

    /** 新的 request code 從這裡開始，避免跟舊版（v0.5 以前）的小數字撞在一起 */
    private const val BASE = 1_000_000

    private fun epoch(t: LocalDateTime): Long =
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun scheduleAll(ctx: Context) {
        Store.items.value.forEach {
            cancelLegacy(ctx, it.id)
            schedule(ctx, it)
        }
    }

    /** 重新排程：準時響鈴，以及（有設定且還沒過）的提前提醒 */
    fun schedule(ctx: Context, item: Item) {
        cancel(ctx, item.id)
        if (!item.enabled) return
        val now = LocalDateTime.now()
        val t = item.nextTrigger(now) ?: return
        setClock(ctx, item.id, epoch(t), MAIN, epoch(t))
        item.nextAdvance(now)?.let { setClock(ctx, item.id, epoch(it), ADV, epoch(t)) }
    }

    fun snooze(ctx: Context, id: Long, minutes: Int) {
        setClock(ctx, id, System.currentTimeMillis() + minutes * 60_000L, SNOOZE, 0L)
    }

    /** 取消這一筆所有排定的響鈴（不處理「待確認」，那是 Advance.clear 的事） */
    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (k in intArrayOf(MAIN, SNOOZE, ADV, NAG)) am.cancel(pi(ctx, id, k, 0L))
    }

    fun cancelKind(ctx: Context, id: Long, kind: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id, kind, 0L))
    }

    fun setClock(ctx: Context, id: Long, millis: Long, kind: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi(ctx, id, kind, at))
    }

    fun pi(ctx: Context, id: Long, kind: Int, at: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", id)
            .putExtra("kind", kind)
            .putExtra("at", at)
        val req = BASE + (id.toInt() and 0xFFFFF) * 8 + kind
        return PendingIntent.getBroadcast(
            ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 清掉 v0.5 以前用過的舊 request code，避免更新後重複響 */
    private fun cancelLegacy(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (s in 0..1) {
            val old = PendingIntent.getBroadcast(
                ctx, id.toInt() * 2 + s, Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            if (old != null) {
                am.cancel(old)
                old.cancel()
            }
        }
    }
}
