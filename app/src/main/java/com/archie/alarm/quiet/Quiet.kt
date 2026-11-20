package com.archie.alarm.quiet

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.archie.alarm.MainActivity
import com.archie.alarm.R

/**
 * 一鍵安靜的核心：開啟時記下原本的設定，關閉時全部還原。
 * 鬧鐘提醒（同一個 App）響之前會先問 [isOn]，安靜中就不響。
 */
object Quiet {
    const val ACTION_TOGGLE = "com.archie.alarm.quiet.TOGGLE"
    const val ACTION_AUTO_OFF = "com.archie.alarm.quiet.AUTO_OFF"

    /** 忘了關的保險：開啟後約 4 小時自動恢復。 */
    const val AUTO_OFF_MS = 4L * 60 * 60 * 1000

    private const val PREFS = "quiet_state"

    /** 安靜時的亮度（0～255）。v0.16 用 1 太暗看不到，改成 25：在暗處看得清楚、又不刺眼。 */
    const val QUIET_BRIGHTNESS = 25
    private const val MAX_EVENTS = 30
    private const val CH_SKIPPED = "quiet_skipped"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 現在是不是安靜中。超過 4 小時一律當作已結束並自動還原——
     * 就算自動恢復的排程被系統延誤，鬧鐘也絕不會被擋超過 4 小時。
     */
    fun isOn(c: Context): Boolean = try {
        val p = prefs(c)
        val on = p.getBoolean("on", false)
        val started = p.getLong("startedAt", 0L)
        if (on && started > 0 && System.currentTimeMillis() - started >= AUTO_OFF_MS) {
            turnOff(c)
            false
        } else {
            on
        }
    } catch (_: Exception) {
        false
    }

    fun startedAt(c: Context): Long = prefs(c).getLong("startedAt", 0L)

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------- 權限 ----------

    data class Perms(
        val dnd: Boolean,
        val brightness: Boolean,
        val contacts: Boolean,
        val screening: Boolean,
    ) {
        val all: Boolean get() = dnd && brightness && contacts && screening
    }

    fun perms(c: Context): Perms {
        val dnd = try {
            nm(c).isNotificationPolicyAccessGranted
        } catch (_: Exception) {
            false
        }
        val bright = Settings.System.canWrite(c)
        val contacts = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val screening = if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = c.getSystemService(RoleManager::class.java)
                rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                    rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return Perms(dnd, bright, contacts, screening)
    }

    // ---------- 手機「實際」的狀態（畫面上的打勾只看這個，不看有沒有送出指令） ----------

    data class Applied(
        val ringer: Boolean,
        val dnd: Boolean,
        val brightness: Boolean,
        val calls: Boolean,
        val contacts: Boolean,
    )

    private fun dndActive(c: Context): Boolean = try {
        when (nm(c).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    fun applied(c: Context): Applied {
        val dnd = dndActive(c)
        val ringer = dnd || try {
            am(c).ringerMode == AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {
            false
        }
        val bright = try {
            val cr = c.contentResolver
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 1) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL &&
                Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255) <= QUIET_BRIGHTNESS + 10
        } catch (_: Exception) {
            false
        }
        val p = perms(c)
        return Applied(ringer, dnd, bright, p.screening, p.contacts)
    }

    // ---------- 開關 ----------

    fun toggle(c: Context) {
        if (isOn(c)) turnOff(c) else turnOn(c)
    }

    fun turnOn(c: Context) {
        if (isOn(c)) return
        val p = prefs(c)
        val nm = nm(c)
        val am = am(c)
        val cr = c.contentResolver
        val e = p.edit()

        // 1. 記下原本的狀態
        e.putInt("prevFilter", nm.currentInterruptionFilter)
        e.putInt("prevRinger", am.ringerMode)
        e.putBoolean("prevPowerSave", isPowerSave(c))
        val canBright = Settings.System.canWrite(c)
        if (canBright) {
            e.putInt("prevBright", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
            e.putInt(
                "prevBrightMode",
                Settings.System.getInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                ),
            )
        }
        e.putBoolean("a_bright", false)
        e.putBoolean("policySaved", false)
        if (nm.isNotificationPolicyAccessGranted && Build.VERSION.SDK_INT >= 28) {
            try {
                val old = nm.notificationPolicy
                e.putInt("pCats", old.priorityCategories)
                    .putInt("pCall", old.priorityCallSenders)
                    .putInt("pMsg", old.priorityMessageSenders)
                    .putInt("pVis", old.suppressedVisualEffects)
                if (Build.VERSION.SDK_INT >= 30) e.putInt("pConv", old.priorityConversationSenders)
                e.putBoolean("policySaved", true)
            } catch (_: Exception) {
            }
        }

        // 2. 螢幕調暗（不是最暗，要看得到）
        if (canBright) {
            try {
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, QUIET_BRIGHTNESS)
                e.putBoolean("a_bright", true)
            } catch (_: Exception) {
            }
        }

        val now = System.currentTimeMillis()
        e.putBoolean("on", true)
            .putLong("startedAt", now)
            .putString("calls", "")
            .putString("skipped", "")
            .commit()

        // 3. 鈴聲靜音、勿擾（不留例外）
        applySilence(c)

        scheduleAutoOff(c, now + AUTO_OFF_MS)
        refreshViews(c)
    }

    /**
     * 套用（或重新套用）鈴聲靜音與勿擾。
     * 勿擾一律「先關再開」：讓三星和手錶確實收到「勿擾打開了」的變化（v0.3 第二次常常沒生效）。
     */
    private fun applySilence(c: Context) {
        val nm = nm(c)
        val am = am(c)
        val silence = {
            try {
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (_: Exception) {
            }
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            silence()
            return
        }
        try {
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) nm.notificationPolicy = quietPolicy()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (_: Exception) {
            try {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } catch (_: Exception) {
            }
        }
        // 勿擾開好之後再設鈴聲靜音（先設的話，關勿擾那一下可能把鈴聲改回來）
        silence()
    }

    /** 安靜中，打開 App 時發現勿擾沒開著（被系統或手動關掉）→ 重新套用。 */
    fun reapplyIfNeeded(c: Context) {
        if (!isOn(c)) return
        if (!dndActive(c)) applySilence(c)
    }

    fun turnOff(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val nm = nm(c)
        val am = am(c)
        val cr = c.contentResolver

        // 先標記結束，鬧鐘馬上恢復正常
        p.edit().putBoolean("on", false).putLong("endedAt", System.currentTimeMillis()).commit()

        // 還原勿擾例外與勿擾，再還原鈴聲（勿擾開著時改鈴聲可能被擋）
        if (nm.isNotificationPolicyAccessGranted) {
            if (p.getBoolean("policySaved", false) && Build.VERSION.SDK_INT >= 28) {
                try {
                    nm.notificationPolicy = savedPolicy(p)
                } catch (_: Exception) {
                }
            }
            var prev = p.getInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL)
            if (prev == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                prev = NotificationManager.INTERRUPTION_FILTER_ALL
            }
            try {
                nm.setInterruptionFilter(prev)
            } catch (_: Exception) {
            }
        }
        try {
            am.ringerMode = p.getInt("prevRinger", AudioManager.RINGER_MODE_NORMAL)
        } catch (_: Exception) {
        }
        if (p.getBoolean("a_bright", false) && Settings.System.canWrite(c)) {
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.getInt("prevBright", 128))
                Settings.System.putInt(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    p.getInt("prevBrightMode", Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL),
                )
            } catch (_: Exception) {
            }
        }
        cancelAutoOff(c)
        refreshViews(c)
    }

    // ---------- 勿擾例外 ----------

    /**
     * 安靜中的勿擾：沒有任何例外；通知進來不跳出、不閃燈、不在螢幕關閉時亮起。
     * 注意：不再擋「全螢幕通知」——v0.2/v0.3 擋了它，鬧鐘有聲音時響鈴畫面卻被藏起來、關不掉。
     */
    private fun quietPolicy(): NotificationManager.Policy {
        val vis = NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
            NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
            NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT
        val starred = NotificationManager.Policy.PRIORITY_SENDERS_STARRED
        return if (Build.VERSION.SDK_INT >= 30) {
            NotificationManager.Policy(
                0, starred, starred, vis,
                NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
            )
        } else {
            NotificationManager.Policy(0, starred, starred, vis)
        }
    }

    private fun savedPolicy(p: SharedPreferences): NotificationManager.Policy {
        val cats = p.getInt("pCats", 0)
        val call = p.getInt("pCall", NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
        val msg = p.getInt("pMsg", NotificationManager.Policy.PRIORITY_SENDERS_STARRED)
        val vis = p.getInt("pVis", 0)
        return if (Build.VERSION.SDK_INT >= 30 && p.contains("pConv")) {
            NotificationManager.Policy(cats, call, msg, vis, p.getInt("pConv", 0))
        } else {
            NotificationManager.Policy(cats, call, msg, vis)
        }
    }

    // ---------- 省電模式（系統不讓 App 直接開關，只能幫忙打開設定頁） ----------

    fun isPowerSave(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (_: Exception) {
        false
    }

    /** 關閉安靜後，省電模式是安靜期間才打開的 → 提醒要不要關。 */
    fun powerSaveLeftOn(c: Context): Boolean =
        !isOn(c) && isPowerSave(c) && !prefs(c).getBoolean("prevPowerSave", true)

    // ---------- 開機後補排自動恢復 ----------

    fun onBoot(c: Context) {
        if (isOn(c)) {
            val at = startedAt(c) + AUTO_OFF_MS
            if (at <= System.currentTimeMillis()) turnOff(c) else scheduleAutoOff(c, at)
        }
        refreshViews(c)
    }

    private fun autoOffIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 71,
        Intent(c, QuietReceiver::class.java).setAction(ACTION_AUTO_OFF),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleAutoOff(c: Context, at: Long) {
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, autoOffIntent(c))
        } catch (_: Exception) {
        }
    }

    private fun cancelAutoOff(c: Context) {
        try {
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(autoOffIntent(c))
        } catch (_: Exception) {
        }
    }

    // ---------- 安靜期間擋下的來電、略過的鬧鐘 ----------

    data class BlockedCall(val time: Long, val number: String, val name: String?)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun appendLine(c: Context, key: String, line: String) {
        val p = prefs(c)
        synchronized(this) {
            val lines = p.getString(key, "").orEmpty()
                .split('\n').filter { it.isNotBlank() }.toMutableList()
            lines.add(line)
            while (lines.size > MAX_EVENTS) lines.removeAt(0)
            p.edit().putString(key, lines.joinToString("\n")).commit()
        }
    }

    fun recordCall(c: Context, number: String) {
        val name = contactName(c, number)
        appendLine(c, "calls", "${System.currentTimeMillis()}\t${clean(number)}\t${clean(name.orEmpty())}")
    }

    fun calls(c: Context): List<BlockedCall> =
        prefs(c).getString("calls", "").orEmpty().split('\n').mapNotNull { line ->
            val f = line.split('\t')
            val t = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            BlockedCall(t, f.getOrNull(1).orEmpty(), f.getOrNull(2)?.takeIf { it.isNotBlank() })
        }

    data class SkippedAlarm(val time: Long, val text: String)

    /**
     * 鬧鐘提醒在安靜中要響時呼叫：不響、不震、不亮螢幕，
     * 只記下來並留一則沒有聲音的通知，事後看得到。
     */
    fun recordSkippedAlarm(c: Context, id: Long, timeText: String, label: String) {
        val text = label.ifBlank { "鬧鐘" }
        appendLine(c, "skipped", "${System.currentTimeMillis()}\t${clean(text)}")
        try {
            val nm = nm(c)
            ensureSkippedChannel(c)
            val open = PendingIntent.getActivity(
                c, 72, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(c, CH_SKIPPED)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("安靜中略過：$timeText $text")
                .setContentText("一鍵安靜開著，所以這次沒有響")
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(4000 + (id.toInt() and 0xFFFF), n)
        } catch (_: Exception) {
        }
    }

    private fun ensureSkippedChannel(c: Context) {
        nm(c).createNotificationChannel(
            NotificationChannel(CH_SKIPPED, "安靜中略過的鬧鐘", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** 給響鈴服務在安靜中「立刻結束」用的前景通知：沒有聲音、不開全螢幕畫面。 */
    fun silentNotification(c: Context): android.app.Notification {
        try {
            ensureSkippedChannel(c)
        } catch (_: Exception) {
        }
        return NotificationCompat.Builder(c, CH_SKIPPED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("安靜中")
            .setSilent(true)
            .build()
    }

    fun skipped(c: Context): List<SkippedAlarm> =
        prefs(c).getString("skipped", "").orEmpty().split('\n').mapNotNull { line ->
            val f = line.split('\t')
            val t = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            SkippedAlarm(t, f.getOrNull(1).orEmpty())
        }

    fun clearEvents(c: Context) {
        prefs(c).edit().putString("calls", "").putString("skipped", "").apply()
    }

    private fun contactName(c: Context, number: String): String? {
        if (number.isBlank()) return null
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number),
            )
            c.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 更新桌面小工具與快速設定 ----------

    fun refreshViews(c: Context) {
        try {
            QuietWidget.updateAll(c)
        } catch (_: Exception) {
        }
        try {
            TileService.requestListeningState(c, ComponentName(c, QuietTile::class.java))
        } catch (_: Exception) {
        }
    }
}
