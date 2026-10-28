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
    val next = remember(items) {
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
        Scheduler.schedule(ctx, i)
        val n = LocalDateTime.now()
        msg = i.nextTrigger(n)?.let { "已儲存，${Fmt.until(n, it)}響鈴" } ?: "已儲存"
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
            Text("鬧鐘提醒", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = cs.onBackground)
            Text(
                "作者：ArchieKUO　v$version",
                color = Gold, fontSize = 15.sp, fontStyle = FontStyle.Italic,
                maxLines = 1, softWrap = false,
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
                Text(
                    "下一個響鈴・${Fmt.dayLabel(t.toLocalDate(), n.toLocalDate())}・${Fmt.until(n, t)}",
                    fontSize = 12.sp, color = cs.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Text(
                    "${i.timeText()} ${i.label}".trim(),
                    fontSize = 22.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer
                )
            } else {
                Text("沒有啟用中的鬧鐘", fontSize = 16.sp, color = cs.onPrimaryContainer)
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
                    QuickRow(onAdd = { i ->
                        val u = i.copy(id = Store.nextId())
                        Store.upsert(u)
                        afterSave(u)
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
                    selected.forEach { Scheduler.cancel(ctx, it) }
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
        if (item.mask == 0 && item.dateEpochDay != null) Fmt.dayLabel(LocalDate.ofEpochDay(item.dateEpochDay), today)
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
            Text(sub, fontSize = 13.sp, color = cs.onSurfaceVariant.copy(alpha = a), maxLines = 1)
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
private fun Dot(text: String, on: Boolean, alpha: Float, modifier: Modifier, size: Int, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(CircleShape)
            .background(if (on) cs.primary.copy(alpha = alpha) else cs.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = size.sp,
            color = if (on) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

// ───────────────────────── 編輯列（只有這一列出現輸入欄） ─────────────────────────

@Composable
private fun EditRow(item: Item, onCommit: (Item) -> Unit, onCancel: () -> Unit, onTest: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val today = LocalDate.now()

    val t0 = item.timeText()
    var time by remember(item.id) { mutableStateOf(TextFieldValue(t0, TextRange(0, t0.length))) }
    var label by remember(item.id) { mutableStateOf(TextFieldValue(item.label, TextRange(item.label.length))) }
    val d0 = item.dateEpochDay?.let { Parser.formatDate(LocalDate.ofEpochDay(it), today) } ?: ""
    var date by remember(item.id) { mutableStateOf(TextFieldValue(d0, TextRange(d0.length))) }
    var mask by remember(item.id) { mutableIntStateOf(item.mask) }
    var snooze by remember(item.id) { mutableIntStateOf(item.snoozeMin) }
    var snoozeActive by remember(item.id) { mutableStateOf(false) }
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    var timeError by remember(item.id) { mutableStateOf<String?>(null) }
    var dateError by remember(item.id) { mutableStateOf<String?>(null) }

    val timeFr = remember { FocusRequester() }
    val dateFr = remember { FocusRequester() }
    val labelFr = remember { FocusRequester() }

    LaunchedEffect(item.id) { timeFr.requestFocus() }

    fun commit() {
        val tm = Parser.parseTime(time.text)
        if (tm == null) {
            timeError = "時間格式不對，例如 07:30"
            timeFr.requestFocus()
            return
        }
        var epoch: Long? = null
        if (mask == 0 && date.text.isNotBlank()) {
            val d = Parser.parseDate(date.text, today)
            if (d == null) {
                dateError = "日期看不懂，例如 明天、10/28"
                dateFr.requestFocus()
                return
            }
            epoch = d.toEpochDay()
        }
        val u = item.copy(
            hour = tm.first, minute = tm.second, label = label.text.trim(),
            mask = mask, dateEpochDay = if (mask == 0) epoch else null,
            snoozeMin = snooze, enabled = true,
        )
        if (u.nextTrigger(LocalDateTime.now()) == null) {
            dateError = "這個時間已經過了"
            if (mask == 0) dateFr.requestFocus()
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cell(
                value = time, onValueChange = { time = it; timeError = null; dateError = null },
                modifier = Modifier.width(104.dp), fr = timeFr,
                placeholder = "07:30", keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                isError = timeError != null,
                onFocusChange = { if (it) snoozeActive = false },
                onAction = { if (mask == 0) dateFr.requestFocus() else labelFr.requestFocus() },
            )
            Cell(
                value = label, onValueChange = { label = it },
                modifier = Modifier.weight(1f), fr = labelFr,
                placeholder = "內容（可留空）", imeAction = ImeAction.Done,
                onFocusChange = { if (it) snoozeActive = false },
                onAction = { commit() },
            )
        }
        timeError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("每天", mask == 127, Modifier.weight(1f)) { mask = 127; dateError = null }
            Chip("工作日", mask == 31, Modifier.weight(1f)) { mask = 31; dateError = null }
            Chip("週末", mask == 96, Modifier.weight(1f)) { mask = 96; dateError = null }
            Chip("僅一次", mask == 0, Modifier.weight(1f)) { mask = 0 }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Fmt.DOW.forEachIndexed { i, c ->
                val on = mask and (1 shl i) != 0
                Dot(c.toString(), on, 1f, Modifier.size(38.dp), 14) { mask = mask xor (1 shl i) }
            }
        }

        if (mask == 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日期", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Cell(
                    value = date, onValueChange = { date = it; dateError = null },
                    modifier = Modifier.width(150.dp), fr = dateFr,
                    placeholder = "留空＝下一次", imeAction = ImeAction.Next,
                    isError = dateError != null,
                    onFocusChange = { if (it) snoozeActive = false },
                    onAction = { labelFr.requestFocus() },
                )
            }
        }
        dateError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

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
private fun QuickRow(onAdd: (Item) -> Unit) {
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
            onAdd(r.item)
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
                placeholder = "週五 18:00 倒垃圾",
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
