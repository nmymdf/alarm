package com.archie.alarm.quiet

import com.archie.alarm.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 顏色（深色、綠色發光、金色省電） ----------------

object Pal {
    val Bg = Color(0xFF0A1013)
    val BgTop = Color(0xFF0F1A1E)
    val Card = Color(0xFF141C21)
    val CardBorder = Color(0xFF28343B)
    val Text = Color(0xFFF1F4F5)
    val Sub = Color(0xFFAAB4BA)
    val Green = Color(0xFF39E26A)
    val GreenFillIn = Color(0xFF0F3A1E)
    val GreenFillOut = Color(0xFF06160C)
    val BadgeOk = Color(0xFF0D2A17)
    val Red = Color(0xFFFF6B6B)
    val BadgeBad = Color(0xFF2E1414)
    val GoldText = Color(0xFFFFC53D)
    val GoldBorder = Color(0xFFC9951C)
    val GoldCardA = Color(0xFF30260F)
    val GoldCardB = Color(0xFF1C1A12)
    val PillTop = Color(0xFFFFE27A)
    val PillBottom = Color(0xFFF6B300)
    val PillText = Color(0xFF2A1C00)
    val Orange = Color(0xFFFF9F1C)
    val Author = Color(0xFFC9A227)
}

@Composable
fun QuietTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Green,
            background = Pal.Bg,
            surface = Pal.Card,
            onBackground = Pal.Text,
            onSurface = Pal.Text,
        ),
        content = content,
    )
}

// ---------------- 資料 ----------------

data class ScreenState(
    val on: Boolean,
    val perms: Quiet.Perms,
    val applied: Quiet.Applied,
    val calls: List<Quiet.BlockedCall>,
    val skipped: List<Quiet.SkippedAlarm>,
    val startedAt: Long,
    val powerSave: Boolean,
    val powerLeftOn: Boolean,
    val version: String,
)

class ScreenActions(
    val onToggle: () -> Unit = {},
    val onDnd: () -> Unit = {},
    val onBrightness: () -> Unit = {},
    val onContacts: () -> Unit = {},
    val onScreening: () -> Unit = {},
    val onAppInfo: () -> Unit = {},
    val onPowerSave: () -> Unit = {},
    val onClearCalls: () -> Unit = {},
)

private fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.TAIWAN).format(Date(t))

// ---------------- 主畫面 ----------------

@Composable
fun MainScreen(s: ScreenState, a: ScreenActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.Bg),
    ) {
        GlowBackground()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header()
                Spacer(Modifier.height(14.dp))

                if (!s.perms.all) {
                    SetupCard(s.perms, a)
                    Spacer(Modifier.height(16.dp))
                }

                BigCircle(on = s.on, onClick = a.onToggle)
                Spacer(Modifier.height(14.dp))

                if (s.on) {
                    OnContent(s, a)
                } else {
                    if (s.powerLeftOn) {
                        PowerLeftCard(a.onPowerSave)
                        Spacer(Modifier.height(14.dp))
                    }
                    if (s.calls.isNotEmpty() || s.skipped.isNotEmpty()) {
                        EventsCard(s.calls, s.skipped, a.onClearCalls)
                        Spacer(Modifier.height(14.dp))
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "作者：ArchieKUO　v${s.version}",
                    color = Pal.Author,
                    fontStyle = FontStyle.Italic,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OnContent(s: ScreenState, a: ScreenActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusItem(s.applied.ringer, R.drawable.ic_m_volume_up, "鈴聲靜音")
        StatusItem(s.applied.dnd, R.drawable.ic_m_layers, "勿擾全擋（含鬧鐘）")
        StatusItem(s.applied.brightness, R.drawable.ic_m_light_mode, "螢幕調暗")
        StatusItem(
            s.applied.calls && s.applied.contacts,
            R.drawable.ic_m_call,
            "來電全部拒接",
            sub = "含連續打來的電話",
        )
    }
    Spacer(Modifier.height(12.dp))
    val blocked = s.calls.size
    val skippedN = s.skipped.size
    if (blocked > 0 || skippedN > 0) {
        val parts = buildList {
            if (blocked > 0) add("擋下 $blocked 通來電")
            if (skippedN > 0) add("略過 $skippedN 個鬧鐘")
        }
        Text(
            "已" + parts.joinToString("、"),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Pal.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
    }
    Text(
        "${hhmm(s.startedAt + Quiet.AUTO_OFF_MS)} 左右會自動恢復",
        fontSize = 17.sp,
        color = Pal.Sub,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(14.dp))
    PowerCard(s.powerSave, a.onPowerSave)
    Spacer(Modifier.height(14.dp))
    WatchPill()
}

// ---------------- 背景與標題 ----------------

@Composable
private fun GlowBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Pal.BgTop, Pal.Bg, Pal.Bg)))
        val w = size.width
        val h = size.height
        // 從左下往右上彎過去的綠色光帶（大圓圈後面）
        fun ribbon(y0: Float, y1: Float, bend: Float, alpha: Float, width: Float) {
            val p = Path().apply {
                moveTo(-0.05f * w, y0 * h)
                cubicTo(0.30f * w, (y0 - 0.02f) * h, 0.60f * w, (y1 + bend) * h, 1.05f * w, y1 * h)
            }
            drawPath(p, Pal.Green.copy(alpha = alpha * 0.10f), style = Stroke(width * 10f, cap = StrokeCap.Round))
            drawPath(p, Pal.Green.copy(alpha = alpha * 0.25f), style = Stroke(width * 3.5f, cap = StrokeCap.Round))
            drawPath(p, Pal.Green.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round))
        }
        val px = 1.dp.toPx()
        ribbon(0.47f, 0.17f, 0.10f, 0.55f, 2.2f * px)
        ribbon(0.52f, 0.24f, 0.12f, 0.35f, 1.6f * px)
        ribbon(0.57f, 0.31f, 0.13f, 0.22f, 1.2f * px)
    }
}

@Composable
private fun Header() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            GlowLines(mirror = false)
            Spacer(Modifier.width(12.dp))
            ShieldBell()
            Spacer(Modifier.width(12.dp))
            GlowLines(mirror = true)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "一鍵安靜",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = Pal.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GlowLines(mirror: Boolean) {
    val long = @Composable {
        Box(
            Modifier
                .width(64.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        if (mirror) listOf(Pal.Green, Pal.Green.copy(alpha = 0.15f))
                        else listOf(Pal.Green.copy(alpha = 0.15f), Pal.Green),
                    ),
                ),
        )
    }
    val short = @Composable {
        Box(
            Modifier
                .width(30.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Pal.Green.copy(alpha = 0.55f)),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mirror) {
            short()
            Spacer(Modifier.width(8.dp))
            long()
        } else {
            long()
            Spacer(Modifier.width(8.dp))
            short()
        }
    }
}

@Composable
private fun ShieldBell() {
    Box(
        modifier = Modifier
            .size(58.dp)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Pal.Green.copy(alpha = 0.30f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_m_shield),
            contentDescription = null,
            tint = Pal.Green,
            modifier = Modifier.size(58.dp),
        )
        Icon(
            painterResource(R.drawable.ic_m_notifications_off),
            contentDescription = null,
            tint = Pal.Green,
            modifier = Modifier
                .padding(bottom = 3.dp)
                .size(24.dp),
        )
    }
}

// ---------------- 大圓圈 ----------------

@Composable
fun BigCircle(on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(272.dp)
            .drawBehind {
                if (on) {
                    drawCircle(
                        Brush.radialGradient(
                            0.0f to Pal.Green.copy(alpha = 0.45f),
                            0.80f to Pal.Green.copy(alpha = 0.40f),
                            1.0f to Color.Transparent,
                            center = center,
                            radius = size.minDimension / 2f,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(228.dp)
                .clip(CircleShape)
                .background(
                    if (on) {
                        Brush.radialGradient(listOf(Pal.GreenFillIn, Pal.GreenFillOut))
                    } else {
                        Brush.radialGradient(listOf(Color(0xFF18221F), Color(0xFF0C1311)))
                    },
                )
                .border(if (on) 6.dp else 4.dp, if (on) Pal.Green else Color(0xFF55705F), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(if (on) R.drawable.ic_m_notifications_off else R.drawable.ic_m_notifications),
                    contentDescription = null,
                    tint = if (on) Pal.Green else Pal.Text,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (on) "安靜中" else "開始安靜",
                    fontSize = if (on) 44.sp else 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Pal.Green else Pal.Text,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (on) "點一下恢復" else "點一下全部安靜",
                    fontSize = 20.sp,
                    color = Pal.Sub,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------- 各種卡片 ----------------

@Composable
private fun DarkCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.Card.copy(alpha = 0.94f))
            .border(1.dp, Pal.CardBorder, shape),
    ) {
        content()
    }
}

@Composable
fun CheckBadge(ok: Boolean, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (ok) Pal.BadgeOk else Pal.BadgeBad)
            .border(2.dp, if (ok) Pal.Green else Pal.Red, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (ok) R.drawable.ic_m_check else R.drawable.ic_m_close),
            contentDescription = null,
            tint = if (ok) Pal.Green else Pal.Red,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun StatusItem(ok: Boolean, icon: Int, label: String, sub: String? = null) {
    DarkCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBadge(ok)
            Spacer(Modifier.width(14.dp))
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = Pal.Text,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (ok) label else "$label（未設定）",
                    fontSize = 20.sp,
                    color = Pal.Text,
                )
                if (sub != null) {
                    Text(sub, fontSize = 17.sp, color = Pal.Sub)
                }
            }
        }
    }
}

@Composable
fun GoldPill(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(Pal.PillTop, Pal.PillBottom)))
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.PillText)
        Icon(
            painterResource(R.drawable.ic_m_chevron_right),
            contentDescription = null,
            tint = Pal.PillText,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun GoldCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Pal.GoldCardA, Pal.GoldCardB)))
            .border(1.5.dp, Pal.GoldBorder, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun PowerCard(on: Boolean, onGo: () -> Unit) {
    GoldCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    CheckBadge(true, 34.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(3.dp, Pal.Orange, CircleShape)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFF3E0)),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    painterResource(R.drawable.ic_m_bolt),
                    contentDescription = null,
                    tint = Pal.GoldText,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "省電模式",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pal.GoldText,
                    modifier = Modifier.weight(1f),
                )
                if (on) {
                    Text("已開啟", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.Green)
                } else {
                    GoldPill("去打開", onGo)
                }
            }
            if (!on) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "省電要自己按一下開關\n（系統不讓 App 自動開）",
                    fontSize = 17.sp,
                    color = Pal.Sub,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WatchPill() {
    DarkCard(shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_m_description),
                contentDescription = null,
                tint = Pal.Text,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("記得手錶也開「劇院模式」", fontSize = 21.sp, color = Pal.Text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PowerLeftCard(onGo: () -> Unit) {
    GoldCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_m_bolt),
                    contentDescription = null,
                    tint = Pal.GoldText,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("省電模式還開著", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Pal.GoldText)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "不需要的話，按下面去關掉",
                fontSize = 18.sp,
                color = Pal.Sub,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            GoldPill("去關閉", onGo)
        }
    }
}

@Composable
private fun EventsCard(
    calls: List<Quiet.BlockedCall>,
    skipped: List<Quiet.SkippedAlarm>,
    onClear: () -> Unit,
) {
    // 來電與略過的鬧鐘混在一起，最新的在上面
    val rows: List<Triple<Long, Int, String>> = (
        calls.map { Triple(it.time, R.drawable.ic_m_call, it.name ?: it.number.ifBlank { "未顯示號碼" }) } +
            skipped.map { Triple(it.time, R.drawable.ic_m_notifications_off, "鬧鐘：${it.text}") }
        ).sortedByDescending { it.first }
    DarkCard {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                "剛才安靜的時候",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Pal.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            rows.forEach { (time, icon, text) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = Pal.Sub,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text,
                        fontSize = 20.sp,
                        color = Pal.Text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(hhmm(time), fontSize = 20.sp, color = Pal.Sub)
                }
            }
            if (calls.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "來電在電話的通話紀錄裡也查得到",
                    fontSize = 17.sp,
                    color = Pal.Sub,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2FBF5B), Color(0xFF1C8A3E))))
                    .clickable(onClick = onClear)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun SetupCard(perms: Quiet.Perms, a: ScreenActions) {
    DarkCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "第一次使用，請完成下面設定",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Pal.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            SetupRow(perms.dnd, "1. 勿擾權限", "找到「鬧鐘提醒」，把開關打開。", a.onDnd)
            Divider()
            SetupRow(perms.brightness, "2. 調整亮度", "把「允許修改系統設定」打開。", a.onBrightness)
            Divider()
            SetupRow(perms.contacts, "3. 讀取聯絡人", "聯絡人打來也能拒接，並顯示是誰打來。", a.onContacts)
            Divider()
            SetupRow(perms.screening, "4. 來電過濾", "跳出詢問時，選「鬧鐘提醒」。", a.onScreening)
            Spacer(Modifier.height(10.dp))
            Text(
                "如果開關是灰色（受限設定）：\n按下面按鈕，點右上角的三個點，\n選「允許受限設定」，再回來設定。",
                fontSize = 17.sp,
                color = Pal.Sub,
                lineHeight = 26.sp,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .border(1.5.dp, Pal.Sub, RoundedCornerShape(50))
                    .clickable(onClick = a.onAppInfo)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("打開應用程式資訊", fontSize = 20.sp, color = Pal.Text)
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Pal.CardBorder),
    )
}

@Composable
private fun SetupRow(done: Boolean, title: String, desc: String, onGo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Pal.Text,
                modifier = Modifier.weight(1f),
            )
            if (done) {
                CheckBadge(true, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text("已完成", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.Green)
            } else {
                GoldPill("去設定", onGo)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(desc, fontSize = 17.sp, color = Pal.Sub)
    }
}

/** 給畫面預覽測試用的範例資料。 */
object Samples {
    val allPerms = Quiet.Perms(dnd = true, brightness = true, contacts = true, screening = true)
    val allApplied = Quiet.Applied(ringer = true, dnd = true, brightness = true, calls = true, contacts = true)
    private val now = 1_763_550_000_000L
    val calls = listOf(
        Quiet.BlockedCall(now - 50 * 60_000L, "0912345678", "王小明"),
        Quiet.BlockedCall(now - 20 * 60_000L, "0223456789", null),
    )

    val skipped = listOf(Quiet.SkippedAlarm(now - 35 * 60_000L, "倒垃圾"))

    fun state(
        on: Boolean,
        perms: Quiet.Perms = allPerms,
        calls: List<Quiet.BlockedCall> = emptyList(),
        skipped: List<Quiet.SkippedAlarm> = emptyList(),
        powerSave: Boolean = false,
        powerLeftOn: Boolean = false,
    ) = ScreenState(on, perms, allApplied, calls, skipped, now - 30 * 60_000L, powerSave, powerLeftOn, "0.17")
}
