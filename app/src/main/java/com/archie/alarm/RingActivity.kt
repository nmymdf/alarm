package com.archie.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Gold = Color(0xFFC9A227)

fun appVersion(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (_: Exception) {
    ""
}

class RingActivity : ComponentActivity() {

    private val stopped = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getLongExtra("id", -1L)
        val item = Store.get(id)
        if (item == null || !RingService.running) {
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this, stopped, IntentFilter(RingService.BROADCAST_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val version = appVersion(this)
        setContent {
            AlarmTheme {
                RingScreen(
                    time = item.timeText(),
                    label = item.label,
                    snoozeMin = item.snoozeMin,
                    version = version,
                    onSnooze = {
                        Scheduler.snooze(this, id, item.snoozeMin)
                        Toast.makeText(this, "${item.snoozeMin} 分鐘後再響", Toast.LENGTH_SHORT).show()
                        stopService(Intent(this, RingService::class.java))
                        finish()
                    },
                    onDismiss = {
                        stopService(Intent(this, RingService::class.java))
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stopped)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}

@Composable
fun RingScreen(
    time: String,
    label: String,
    snoozeMin: Int,
    version: String,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF111418))
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 56.dp)) {
            Text(time, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Medium)
            if (label.isNotBlank()) {
                Text(
                    label, color = Color(0xFFDADDE2), fontSize = 26.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF2B3139))
                    .clickable(onClick = onSnooze),
                contentAlignment = Alignment.Center,
            ) {
                Text("稍後提醒 $snoozeMin 分鐘", color = Color.White, fontSize = 18.sp)
            }
            HoldButton("按住 1 秒關閉", onDone = onDismiss)
            Text(
                "作者：ArchieKUO　v$version",
                color = Gold, fontSize = 12.sp, fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun HoldButton(text: String, holdMs: Long = 1000L, onDone: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color(0xFF3A3F47))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        val start = System.currentTimeMillis()
                        while (true) {
                            val p = (System.currentTimeMillis() - start).toFloat() / holdMs
                            progress = p.coerceAtMost(1f)
                            if (p >= 1f) {
                                onDone()
                                break
                            }
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(Gold)
                .align(Alignment.CenterStart)
        )
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}
