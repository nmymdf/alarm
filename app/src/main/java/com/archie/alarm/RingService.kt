package com.archie.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class RingService : Service() {

    companion object {
        const val ACTION_STOP = "com.archie.alarm.STOP"
        const val ACTION_SNOOZE = "com.archie.alarm.SNOOZE"
        const val BROADCAST_STOPPED = "com.archie.alarm.RING_STOPPED"
        const val TEST_ID = -999L
        private const val CHANNEL = "ring"
        private const val NOTIF_ID = 1001
        private const val MIRROR_ID = 1002
        private const val CH_WATCH = "ring_watch"
        private const val MAX_RING_MS = 5 * 60_000L

        @Volatile
        var running = false

        fun lookup(id: Long): Item? =
            if (id == TEST_ID) {
                val n = java.time.LocalTime.now()
                Item(TEST_ID, n.hour, n.minute, "測試響鈴", 0, null, true, 1)
            } else Store.get(id)
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var advMode = false
    private var curId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        Pending.init(this)
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                Store.get(id)?.let { Scheduler.snooze(this, id, it.snoozeMin) }
                stopSelf()
                return START_NOT_STICKY
            }
        }

        advMode = intent?.getBooleanExtra("adv", false) == true
        curId = id
        val item = lookup(id)
        try {
            startForegroundCompat(buildNotification(id, item, advMode))
        } catch (e: Exception) {
            item?.let { Fallback.post(this, it, "響鈴服務無法前景執行：${e.javaClass.simpleName}") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (item == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        stopSound()
        running = true
        postWatchNotification(id, item, advMode)
        val soundOk = startSound()
        if (!soundOk) Fallback.post(this, item, "找不到可播放的鬧鐘音效，請檢查系統鬧鐘鈴聲")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, MAX_RING_MS)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(id: Long, item: Item?, adv: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "鬧鐘響鈴", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val full = PendingIntent.getActivity(
            this, 0,
            Intent(this, RingActivity::class.java).putExtra("id", id).putExtra("adv", adv)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val snoozePi = PendingIntent.getService(
            this, 1,
            Intent(this, RingService::class.java).setAction(ACTION_SNOOZE).putExtra("id", id), flags
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, RingService::class.java).setAction(ACTION_STOP).putExtra("id", id), flags
        )
        val base = item?.label?.ifBlank { null } ?: "鬧鐘"
        val title = if (adv) "提前提醒・$base" else base
        val text = listOfNotNull(
            item?.timeText()?.let { if (adv) "$it 開始" else it },
            item?.note?.ifBlank { null },
        ).joinToString("・")
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        if (!adv) b.addAction(0, "稍後提醒", snoozePi)
        b.addAction(0, "關閉", stopPi)
        return b.build()
    }

    /**
     * 給 Galaxy Watch 等手錶用的通知。
     * 準時響鈴那則通知是「持續顯示」，手錶通常不會轉發，所以另外發一則一般通知，
     * 手錶收到會震動，並可直接按「稍後提醒」「關閉」。
     */
    private fun postWatchNotification(id: Long, item: Item, adv: Boolean) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WATCH, "鬧鐘（同步到手錶）", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, RingActivity::class.java).putExtra("id", id).putExtra("adv", adv)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                flags
            )
            val snoozePi = PendingIntent.getService(
                this, 1,
                Intent(this, RingService::class.java).setAction(ACTION_SNOOZE).putExtra("id", id), flags
            )
            val stopPi = PendingIntent.getService(
                this, 2,
                Intent(this, RingService::class.java).setAction(ACTION_STOP).putExtra("id", id), flags
            )
            val base = item.label.ifBlank { "鬧鐘" }
            val title = if (adv) "提前提醒・$base" else base
            val text = listOfNotNull(
                if (adv) "${item.timeText()} 開始" else item.timeText(),
                item.note.ifBlank { null },
            ).joinToString("・")
            val b = NotificationCompat.Builder(this, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(false)
                .setOngoing(false)
                .setLocalOnly(false)
                .setContentIntent(open)
                .setDeleteIntent(stopPi)
            if (!adv) b.addAction(0, "稍後提醒", snoozePi)
            b.addAction(0, "關閉", stopPi)
            nm.notify(MIRROR_ID, b.build())
        } catch (_: Exception) {
        }
    }

    /** 回傳是否成功播出聲音；會依序嘗試多個系統音效來源 */
    private fun startSound(): Boolean {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarm:ring").apply {
                acquire(MAX_RING_MS)
            }
        } catch (_: Exception) {
        }

        val uris = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        var ok = false
        for (uri in uris) {
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                ok = true
                break
            } catch (_: Exception) {
                try {
                    player?.release()
                } catch (_: Exception) {
                }
                player = null
            }
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } catch (_: Exception) {
        }
        return ok
    }

    private fun stopSound() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (advMode && curId > 0) Advance.clear(this, curId)
        try {
            getSystemService(NotificationManager::class.java).cancel(MIRROR_ID)
        } catch (_: Exception) {
        }
        sendBroadcast(Intent(BROADCAST_STOPPED).setPackage(packageName))
        super.onDestroy()
    }
}
