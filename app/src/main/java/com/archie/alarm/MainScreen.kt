package com.archie.alarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF6C8EF5),
            onPrimary = Color(0xFF0B1330),
            primaryContainer = Color(0xFF2A3A66),
            onPrimaryContainer = Color(0xFFE8EAF0),
            background = Color(0xFF101319),
            onBackground = Color(0xFFE8EAF0),
            surface = Color(0xFF1A1F29),
            onSurface = Color(0xFFE8EAF0),
            surfaceVariant = Color(0xFF262D3A),
            onSurfaceVariant = Color(0xFFA0A8BA),
            outlineVariant = Color(0xFF333B4A),
            error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2B5FD9),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF2B5FD9),
            onPrimaryContainer = Color.White,
            background = Color(0xFFF3F4F8),
            onBackground = Color(0xFF1B1D24),
            surface = Color.White,
            onSurface = Color(0xFF1B1D24),
            surfaceVariant = Color(0xFFEEF0F6),
            onSurfaceVariant = Color(0xFF5F6575),
            outlineVariant = Color(0xFFD9DCE6),
            error = Color(0xFFD32F2F),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val CardShape = RoundedCornerShape(16.dp)

// ───────────────────────── 主畫面 ─────────────────────────

@Composable
fun MainScreen(permTick: Int, version: String) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val items by Store.items.collectAsState()
    val pendingList by Pending.list.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    val sorted = remember(items) {
        val n = LocalDateTime.now()
        val far = Long.MAX_VALUE / 4
        items.sortedBy {
            val k = it.nextTrigger(n)?.atZone(zone)?.toEpochSecond() ?: far
            if (it.enabled) k else k + far
        }
    }
    val next = remember(items, tick) {
        val n = LocalDateTime.now()
        items.filter { it.enabled }
            .mapNotNull { i -> i.nextTrigger(n)?.let { t -> Triple(i, t, t.atZone(zone).toEpochSecond()) } }
            .minByOrNull { it.third }
            ?.let { (i, t, _) -> Triple(i, t, n) }
    }

    val needNotif = remember(permTick) {
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    val needFullScreen = remember(permTick) {
        Build.VERSION.SDK_INT >= 34 &&
            !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun afterSave(i: Item) {
        Advance.clear(ctx, i.id)
        Scheduler.schedule(ctx, i)
        val n = LocalDateTime.now()
        val base = i.nextTrigger(n)?.let { "已儲存，${Fmt.until(n, it)}響鈴" } ?: "已儲存"
        msg = if (i.advanceMin > 0 && i.nextTrigger(n) != null && i.nextAdvance(n) == null) {
            "$base（提前時間已過，這次不提前）"
        } else base
    }

    fun setEnabled(ids: Set<Long>, on: Boolean) {
        val n = LocalDateTime.now()
        var skipped = 0
        ids.forEach { id ->
            val it = Store.get(id) ?: return@forEach
            if (on && it.nextTrigger(n) == null) {
                skipped++
                return@forEach
            }
            val u = it.copy(enabled = on)
            Store.upsert(u)
            if (!on) Advance.clear(ctx, id)
            Scheduler.schedule(ctx, u)
        }
        msg = if (skipped > 0) "有 $skipped 筆時間已過，請先改日期" else null
    }

    fun testRing() {
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RingService::class.java).putExtra("id", RingService.TEST_ID)
            )
            ctx.startActivity(
                Intent(ctx, RingActivity::class.java).putExtra("id", RingService.TEST_ID)
            )
        } catch (e: Exception) {
            msg = "測試失敗：${e.javaClass.simpleName}"
        }
    }

    BackHandler(enabled = editingId != null) { editingId = null }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "鬧鐘提醒", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = cs.onBackground,
                maxLines = 1, softWrap = false,
            )
            FitText(
                "作者：ArchieKUO v$version",
                maxSize = 15f, minSize = 9f,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
        }

        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .clip(CardShape)
                .background(cs.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (next != null) {
                val (i, t, n) = next
                val a = i.nextAdvance(n)
                Text(
                    "下一個響鈴・${Fmt.dayLabel(t.toLocalDate(), n.toLocalDate())}・${Fmt.until(n, t)}" +
                        (a?.let { "・提前提醒 %02d:%02d".format(it.hour, it.minute) } ?: ""),
                    fontSize = 12.sp, color = cs.onPrimaryContainer.copy(alpha = 0.85f), maxLines = 2,
                )
                Text(
                    "${i.timeText()} ${i.label}".trim(),
                    fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer
                )
            } else {
                Text("沒有啟用中的鬧鐘", fontSize = 16.sp, color = cs.onPrimaryContainer)
            }
        }

        pendingList.forEach { p ->
            val pi = items.firstOrNull { x -> x.id == p.id }
            if (pi != null && p.eventMillis > System.currentTimeMillis()) {
                PendingCard(pi, p) { Advance.clear(ctx, p.id) }
            }
        }

        msg?.let {
            Text(it, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val allSelected = items.isNotEmpty() && selected.size == items.size
            TextButton(onClick = {
                selected = if (allSelected) emptySet() else items.map { it.id }.toSet()
            }) { Text(if (allSelected) "取消全選" else "全選", fontSize = 13.sp) }
            TextButton(onClick = {
                if (selected.isEmpty()) msg = "請先勾選要處理的項目" else setEnabled(selected, true)
            }) { Text("啟用", fontSize = 13.sp) }
            TextButton(onClick = {
                if (selected.isEmpty()) msg = "請先勾選要處理的項目" else setEnabled(selected, false)
            }) { Text("停用", fontSize = 13.sp) }
            TextButton(onClick = {
                if (selected.isEmpty()) msg = "請先勾選要刪除的項目" else confirmDelete = true
            }) { Text("刪除", fontSize = 13.sp, color = cs.error) }
        }

        if (needNotif) {
            Banner("需要「通知」權限，鬧鐘才能響。", "前往設定") {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }
        }
        if (needFullScreen && Build.VERSION.SDK_INT >= 34) {
            Banner("開啟「全螢幕通知」，鎖屏時才會跳出響鈴畫面。", "前往設定") {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                )
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            items(sorted, key = { it.id }) { item ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (editingId == item.id) {
                        EditRow(
                            item = item,
                            onCommit = { u ->
                                Store.upsert(u)
                                afterSave(u)
                                editingId = null
                            },
                            onCancel = { editingId = null },
                            onTest = { testRing() },
                        )
                    } else {
                        ItemRow(
                            item = item,
                            checked = item.id in selected,
                            onCheck = { c -> selected = if (c) selected + item.id else selected - item.id },
                            onToggle = { on -> setEnabled(setOf(item.id), on) },
                            onEdit = { editingId = item.id },
                        )
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    QuickRow(onAdd = { i, defaulted ->
                        val u = i.copy(id = Store.nextId())
                        Store.upsert(u)
                        afterSave(u)
                        if (defaulted) msg = "已儲存。沒聽到時間，先設 09:00，點這筆可以修改。"
                    })
                }
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除 ${selected.size} 筆？") },
            text = { Text("刪除後無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    selected.forEach {
                        Scheduler.cancel(ctx, it)
                        Advance.clear(ctx, it)
                    }
                    Store.delete(selected)
                    selected = emptySet()
                    confirmDelete = false
                    msg = "已刪除"
                }) { Text("刪除", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF4D6))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, color = Color(0xFF5C4400), modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action, fontSize = 13.sp, color = Color(0xFF8A5A00)) }
    }
}

// ───────────────────────── 一般列（只顯示文字） ─────────────────────────

@Composable
private fun ItemRow(
    item: Item,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val a = if (item.enabled) 1f else 0.45f
    val today = LocalDate.now()
    val sub = listOfNotNull(
        if (item.monthDay > 0) "每月 ${item.monthDay} 日"
        else if (item.mask == 0 && item.dateEpochDay != null) Fmt.dayLabel(LocalDate.ofEpochDay(item.dateEpochDay), today)
        else if (item.mask == 0) "僅一次" else null,
        item.label.ifBlank { "鬧鐘" },
    ).joinToString("・")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cs.surface)
            .clickable(onClick = onEdit)
            .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Column(Modifier.weight(1f)) {
            Text(
                item.timeText(), fontSize = 28.sp, fontWeight = FontWeight.Medium,
                color = cs.onSurface.copy(alpha = a),
            )
            Text(sub, fontSize = 14.sp, color = cs.onSurfaceVariant.copy(alpha = a), maxLines = 1)
            if (item.note.isNotBlank()) {
                Text(
                    item.note, fontSize = 12.sp, color = cs.onSurfaceVariant.copy(alpha = 0.75f * a),
                    maxLines = 2, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (item.advanceMin > 0) {
                Tag(
                    "提前 ${Fmt.advShort(item.advanceMin)}" + if (item.advanceImportant) "・重要" else "",
                    item.advanceImportant, a, Modifier.padding(top = 6.dp),
                )
            }
            if (item.mask != 0) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Fmt.DOW.forEachIndexed { i, c ->
                        Dot(c.toString(), item.mask and (1 shl i) != 0, a, Modifier.size(22.dp), 11, null)
                    }
                }
            }
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun Tag(text: String, important: Boolean, alpha: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val c = if (important) cs.error else cs.primary
    Text(
        text, fontSize = 11.sp, color = c.copy(alpha = alpha), maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun PendingCard(item: Item, p: PendingAdv, onAck: () -> Unit) {
    val now = System.currentTimeMillis()
    val detail = listOfNotNull(
        item.note.ifBlank { null },
        Fmt.untilMillis(now, p.eventMillis) + "開始",
    ).joinToString("・")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(CardShape)
            .background(Color(0xFFFFF4D6))
            .border(1.5.dp, Color(0xFFE8B84A), CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("待確認的提前提醒", fontSize = 12.sp, color = Color(0xFF8A5A00))
            Text(
                "${item.timeText()} ${item.label.ifBlank { "鬧鐘" }}",
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF5C4400),
                modifier = Modifier.padding(top = 2.dp), maxLines = 1,
            )
            Text(detail, fontSize = 12.sp, color = Color(0xFF7A6320), modifier = Modifier.padding(top = 2.dp), maxLines = 2)
        }
        Box(
            Modifier
                .width(84.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color(0xFF8A5A00))
                .clickable(onClick = onAck),
            contentAlignment = Alignment.Center,
        ) {
            Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Dot(text: String, on: Boolean, alpha: Float, modifier: Modifier, size: Int, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    // 圓圈裡的字不跟著系統字體放大，否則字會比圓圈高、位置跑掉
    val fs = with(LocalDensity.current) { size.dp.toSp() }
    Box(
        modifier
            .clip(CircleShape)
            .background(if (on) cs.primary.copy(alpha = alpha) else cs.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = fs,
            color = if (on) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                lineHeight = fs,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        )
    }
}

// ───────────────────────── 編輯列（只有這一列出現輸入欄） ─────────────────────────

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        Box(Modifier.width(200.dp)) { content() }
    }
}

@Composable
private fun EditRow(item: Item, onCommit: (Item) -> Unit, onCancel: () -> Unit, onTest: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val today = LocalDate.now()

    val t0 = item.timeText()
    var time by remember(item.id) { mutableStateOf(TextFieldValue(t0, TextRange(0, t0.length))) }
    var label by remember(item.id) { mutableStateOf(TextFieldValue(item.label, TextRange(item.label.length))) }
    var note by remember(item.id) { mutableStateOf(TextFieldValue(item.note, TextRange(item.note.length))) }
    val d0 = item.dateEpochDay?.let { Parser.formatDate(LocalDate.ofEpochDay(it), today) } ?: ""
    var date by remember(item.id) { mutableStateOf(TextFieldValue(d0, TextRange(d0.length))) }
    var mask by remember(item.id) { mutableIntStateOf(item.mask) }
    var monthly by remember(item.id) { mutableStateOf(item.monthDay > 0) }
    val md0 = if (item.monthDay > 0) item.monthDay.toString() else ""
    var monthText by remember(item.id) { mutableStateOf(TextFieldValue(md0, TextRange(md0.length))) }
    val monthFr = remember { FocusRequester() }
    var monthError by remember(item.id) { mutableStateOf<String?>(null) }
    var snooze by remember(item.id) { mutableIntStateOf(item.snoozeMin) }
    var advMin by remember(item.id) { mutableIntStateOf(item.advanceMin) }
    var advImp by remember(item.id) { mutableStateOf(item.advanceImportant) }
    var snoozeActive by remember(item.id) { mutableStateOf(false) }
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    var advActive by remember(item.id) { mutableStateOf(false) }
    var advMenu by remember(item.id) { mutableStateOf(false) }
    var timeError by remember(item.id) { mutableStateOf<String?>(null) }
    var dateError by remember(item.id) { mutableStateOf<String?>(null) }

    val timeFr = remember { FocusRequester() }
    val dateFr = remember { FocusRequester() }
    val labelFr = remember { FocusRequester() }
    val noteFr = remember { FocusRequester() }

    LaunchedEffect(item.id) { timeFr.requestFocus() }

    fun onCellFocus(focused: Boolean) {
        if (focused) {
            snoozeActive = false
            advActive = false
        }
    }

    fun commit() {
        val tm = Parser.parseTime(time.text)
        if (tm == null) {
            timeError = "時間格式不對，例如 07:30"
            timeFr.requestFocus()
            return
        }
        var monthDay = 0
        if (monthly) {
            val v = monthText.text.trim().toIntOrNull()
            if (v == null || v !in 1..31) {
                monthError = "請填 1 到 31 的號數"
                monthFr.requestFocus()
                return
            }
            monthDay = v
        }
        var epoch: Long? = null
        if (!monthly && mask == 0 && date.text.isNotBlank()) {
            val d = Parser.parseDate(date.text, today)
            if (d == null) {
                dateError = "日期看不懂，例如 明天、10/28"
                dateFr.requestFocus()
                return
            }
            epoch = d.toEpochDay()
        }
        val u = item.copy(
            hour = tm.first, minute = tm.second, label = label.text.trim(), note = note.text.trim(),
            mask = if (monthly) 0 else mask, dateEpochDay = if (!monthly && mask == 0) epoch else null,
            monthDay = monthDay,
            snoozeMin = snooze, enabled = true,
            advanceMin = advMin, advanceImportant = advMin > 0 && advImp,
        )
        if (u.nextTrigger(LocalDateTime.now()) == null) {
            dateError = "這個時間已經過了"
            if (!monthly && mask == 0) dateFr.requestFocus()
            return
        }
        focus.clearFocus()
        onCommit(u)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cs.surface)
            .border(2.dp, cs.primary, CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(
                value = time, onValueChange = { time = it; timeError = null; dateError = null },
                modifier = Modifier.width(104.dp), fr = timeFr,
                placeholder = "07:30", keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                isError = timeError != null,
                onFocusChange = { onCellFocus(it) },
                onAction = { if (monthly) monthFr.requestFocus() else if (mask == 0) dateFr.requestFocus() else labelFr.requestFocus() },
            )
            Cell(
                value = label, onValueChange = { label = it },
                modifier = Modifier.weight(1f), fr = labelFr,
                placeholder = "內容（可留空）", imeAction = ImeAction.Next,
                onFocusChange = { onCellFocus(it) },
                onAction = { noteFr.requestFocus() },
            )
        }
        timeError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

        Cell(
            value = note, onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(), fr = noteFr,
            placeholder = "備註（可留空）", imeAction = ImeAction.Done,
            onFocusChange = { onCellFocus(it) },
            onAction = { commit() },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("每天", !monthly && mask == 127, Modifier.weight(1f)) { monthly = false; mask = 127; dateError = null }
            Chip("工作日", !monthly && mask == 31, Modifier.weight(1f)) { monthly = false; mask = 31; dateError = null }
            Chip("週末", !monthly && mask == 96, Modifier.weight(1f)) { monthly = false; mask = 96; dateError = null }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("每月", monthly, Modifier.weight(1f)) { monthly = true; mask = 0; dateError = null }
            Chip("僅一次", !monthly && mask == 0, Modifier.weight(1f)) { monthly = false; mask = 0 }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Fmt.DOW.forEachIndexed { i, c ->
                val on = !monthly && mask and (1 shl i) != 0
                Dot(c.toString(), on, 1f, Modifier.size(38.dp), 14) { monthly = false; mask = mask xor (1 shl i) }
            }
        }

        if (monthly) {
            FormRow("每月幾號") {
                Cell(
                    value = monthText, onValueChange = { monthText = it; monthError = null },
                    modifier = Modifier.fillMaxWidth(), fr = monthFr,
                    placeholder = "例如 20", keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                    isError = monthError != null,
                    onFocusChange = { onCellFocus(it) },
                    onAction = { labelFr.requestFocus() },
                )
            }
            Text("31 號遇到小月，會在月底最後一天響", fontSize = 13.sp, color = cs.onSurfaceVariant)
            monthError?.let { Text(it, color = cs.error, fontSize = 12.sp) }
        } else if (mask == 0) {
            FormRow("日期") {
                Cell(
                    value = date, onValueChange = { date = it; dateError = null },
                    modifier = Modifier.fillMaxWidth(), fr = dateFr,
                    placeholder = "留空＝下一次", imeAction = ImeAction.Next,
                    isError = dateError != null,
                    onFocusChange = { onCellFocus(it) },
                    onAction = { labelFr.requestFocus() },
                )
            }
        }
        dateError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))

        FormRow("提前提醒") {
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceVariant)
                        .clickable {
                            focus.clearFocus()
                            snoozeActive = false
                            if (!advActive) advActive = true else advMenu = true
                        }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(Fmt.advBefore(advMin), fontSize = 15.sp, color = cs.onSurface, maxLines = 1)
                    if (advActive) Text("▾", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = advMenu, onDismissRequest = { advMenu = false }) {
                    Fmt.ADVANCE_CHOICES.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(Fmt.advBefore(m)) },
                            onClick = { advMin = m; advMenu = false },
                        )
                    }
                }
            }
        }
        FormRow("提前強度") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (advMin > 0) 1f else 0.4f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("一般", !advImp, Modifier.weight(1f)) { if (advMin > 0) advImp = false }
                Chip("重要", advImp, Modifier.weight(1f)) { if (advMin > 0) advImp = true }
            }
        }
        Text(
            "一般：通知留著，沒確認每 5 分再響（最多 3 次）\n重要：像鬧鐘一樣響，要按住關閉",
            fontSize = 12.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant.copy(alpha = 0.8f),
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box {
                Text(
                    if (snoozeActive) "稍後提醒 $snooze 分 ▾" else "稍後提醒 $snooze 分",
                    fontSize = 14.sp, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            focus.clearFocus()
                            advActive = false
                            if (!snoozeActive) snoozeActive = true else menuOpen = true
                        }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        DropdownMenuItem(
                            text = { Text("$m 分鐘") },
                            onClick = { snooze = m; menuOpen = false },
                        )
                    }
                }
            }
            Text(
                "測試響鈴", fontSize = 14.sp, color = cs.primary, maxLines = 1, softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onTest)
                    .padding(vertical = 10.dp, horizontal = 2.dp),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("取消", false, Modifier.weight(1f), onCancel)
            ActionButton("儲存", true, Modifier.weight(2f)) { commit() }
        }
    }
}

// ───────────────────────── 最下面空白輸入列 ─────────────────────────

@Composable
private fun QuickRow(onAdd: (Item, Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tv by remember { mutableStateOf(TextFieldValue("")) }
    var err by remember { mutableStateOf<String?>(null) }

    fun tryAdd(text: String = tv.text) {
        if (text.isBlank()) {
            err = null
            return
        }
        val r = Parser.parseQuick(text, LocalDateTime.now())
        if (r.item == null) {
            tv = TextFieldValue(text, TextRange(text.length))
            err = r.error
        } else {
            onAdd(r.item, r.defaultedTime)
            tv = TextFieldValue("")
            err = null
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            if (list.isNotEmpty()) {
                val now = LocalDateTime.now()
                val best = list.firstOrNull { Parser.parseQuick(it, now).item != null } ?: list.first()
                tryAdd(best)
            }
        }
    }

    fun startVoice() {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "例如：明天下午三點 打電話給銀行")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            )
        } catch (_: Exception) {
            err = "這支手機沒有語音辨識服務，請改用鍵盤上的麥克風"
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(cs.surface)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(
                value = tv, onValueChange = { tv = it; err = null },
                modifier = Modifier.weight(1f),
                placeholder = "明天 7:00 起床",
                imeAction = ImeAction.Done,
                isError = err != null,
                onFocusChange = { if (!it) tryAdd() },
                onAction = { tryAdd() },
            )
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceVariant)
                    .clickable { startVoice() },
                contentAlignment = Alignment.Center,
            ) { MicIcon(cs.primary) }
            ActionButton("新增", true, Modifier.width(76.dp), height = 46.dp) { tryAdd() }
        }
        err?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
    }
}

@Composable
private fun ActionButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = ActionButton(text, filled, modifier, 48.dp, onClick)

@Composable
private fun ActionButton(
    text: String,
    filled: Boolean,
    modifier: Modifier,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (filled) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
            color = if (filled) cs.onPrimary else cs.onSurface,
        )
    }
}

/** 單行靠右的金色斜體文字；放不下時自動縮小字體，絕不被切掉 */
@Composable
private fun FitText(text: String, maxSize: Float, minSize: Float, modifier: Modifier = Modifier) {
    var size by remember(text) { mutableFloatStateOf(maxSize) }
    Text(
        text,
        modifier = modifier,
        color = Gold,
        fontSize = size.sp,
        fontStyle = FontStyle.Italic,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && size > minSize) size -= 0.5f },
    )
}

@Composable
private fun MicIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(
            color, topLeft = Offset(w * 0.375f, h * 0.08f), size = Size(w * 0.25f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.125f),
        )
        drawArc(
            color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.21f, h * 0.28f), size = Size(w * 0.58f, h * 0.46f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.92f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

// ───────────────────────── 共用元件 ─────────────────────────

@Composable
private fun Cell(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    fr: FocusRequester? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    isError: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onAction: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val border = when {
        isError -> cs.error
        focused -> cs.primary
        else -> Color.Transparent
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .let { if (fr != null) it.focusRequester(fr) else it }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            },
        textStyle = TextStyle(fontSize = 16.sp, color = if (isError) cs.error else cs.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.5.dp, border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text(placeholder, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) cs.onPrimary else cs.onSurface)
    }
}
