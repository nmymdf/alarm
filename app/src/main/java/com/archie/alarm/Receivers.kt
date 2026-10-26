package com.archie.alarm

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getLongExtra("id", -1L)
        val snooze = intent.getBooleanExtra("snooze", false)
        val item = Store.get(id) ?: return
        if (!snooze) {
            if (item.mask == 0) {
                Store.upsert(item.copy(enabled = false))
            } else {
                Scheduler.schedule(ctx, item)
            }
        }
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, RingService::class.java).putExtra("id", id)
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Scheduler.scheduleAll(ctx)
    }
}
