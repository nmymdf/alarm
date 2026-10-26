package com.archie.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

object Scheduler {

    fun scheduleAll(ctx: Context) {
        Store.items.value.forEach { schedule(ctx, it) }
    }

    fun schedule(ctx: Context, item: Item) {
        cancel(ctx, item.id)
        if (!item.enabled) return
        val millis = item.nextEpoch(LocalDateTime.now()) ?: return
        set(ctx, item.id, millis, false)
    }

    fun snooze(ctx: Context, id: Long, minutes: Int) {
        set(ctx, id, System.currentTimeMillis() + minutes * 60_000L, true)
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id, false))
        am.cancel(pending(ctx, id, true))
    }

    private fun set(ctx: Context, id: Long, millis: Long, snooze: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pending(ctx, id, snooze))
    }

    private fun pending(ctx: Context, id: Long, snooze: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", id)
            .putExtra("snooze", snooze)
        val req = id.toInt() * 2 + if (snooze) 1 else 0
        return PendingIntent.getBroadcast(
            ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
