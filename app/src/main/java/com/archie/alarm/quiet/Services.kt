package com.archie.alarm.quiet

import com.archie.alarm.R

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.telecom.Call
import android.telecom.CallScreeningService
import android.widget.RemoteViews
import android.widget.Toast

/** 安靜中：所有來電（含聯絡人、連續打來的）都直接拒接，對方轉語音信箱；通話紀錄仍保留。 */
class QuietScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = Build.VERSION.SDK_INT < 29 ||
            details.callDirection == Call.Details.DIRECTION_INCOMING
        if (incoming && Quiet.isOn(this)) {
            respondToCall(
                details,
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build(),
            )
            try {
                Quiet.recordCall(this, details.handle?.schemeSpecificPart.orEmpty())
            } catch (_: Exception) {
            }
        } else {
            respondToCall(details, CallResponse.Builder().build())
        }
    }
}

/** 下拉快速設定裡的「一鍵安靜」按鈕。 */
class QuietTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        Quiet.toggle(this)
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Quiet.isOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quiet_app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "安靜中" else "關閉"
        tile.updateTile()
    }
}

/** 桌面小工具：點一下開，再點一下關。 */
class QuietWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        fun views(c: Context): RemoteViews {
            val on = Quiet.isOn(c)
            val v = RemoteViews(c.packageName, R.layout.widget_quiet)
            v.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off,
            )
            val fg = if (on) 0xFFFFFFFF.toInt() else 0xFF1B1B1F.toInt()
            v.setImageViewResource(R.id.widget_icon, if (on) R.drawable.ic_quiet else R.drawable.ic_sound)
            v.setInt(R.id.widget_icon, "setColorFilter", fg)
            v.setTextViewText(R.id.widget_text, if (on) "安靜中" else "一鍵安靜")
            v.setTextColor(R.id.widget_text, fg)
            val pi = PendingIntent.getBroadcast(
                c, 0,
                Intent(c, QuietReceiver::class.java).setAction(Quiet.ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, pi)
            return v
        }

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, QuietWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(c))
        }
    }
}

/** 小工具按下、4 小時自動恢復。 */
class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Quiet.ACTION_TOGGLE -> {
                Quiet.toggle(context)
                val msg = if (Quiet.isOn(context)) "已開始安靜" else "已恢復正常"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            Quiet.ACTION_AUTO_OFF -> Quiet.turnOff(context)
        }
    }
}

/** 重新開機或 App 更新後，補排自動恢復並更新小工具。 */
class QuietBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Quiet.onBoot(context)
        }
    }
}
