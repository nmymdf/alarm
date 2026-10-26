package com.archie.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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

private val ErrorRed = Color(0xFFD32F2F)

@Composable
fun AlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2B5FD9),
            background = Color(0xFFF6F7F9),
            surface = Color.White,
        ),
        content = content,
    )
}

// ───────────────────────── 主畫面 ─────────────────────────

@Composable
fun MainScreen(permTick: Int, version: String) {
    val ctx = LocalContext.current
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
    val nextText = remember(items) {
        val n = LocalDateTime.now()
        items.filter { it.enabled }
            .mapNotNull { i -> i.nextTrigger(n)?.let { t -> Triple(i, t, t.atZone(zone).toEpochSecond()) } }
            .minByOrNull { it.third }
            ?.let { (i, t, _) ->
                "下一個：${Fmt.dayLabel(t.toLocalDate(), n.toLocalDate())} ${i.timeText()} ${i.label}・${Fmt.until(n, t)}"
            } ?: "沒有啟用中的鬧鐘"
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

    BackHandler(enabled = editingId != null) { editingId = null }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text("鬧鐘提醒", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                msg ?: nextText, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
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
            }) { Text("刪除", fontSize = 13.sp, color = ErrorRed) }
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(Modifier.weight(1f)) {
            items(sorted, key = { it.id }) { item ->
                if (editingId == item.id) {
                    EditRow(
                        item = item,
                        onCommit = { u ->
                            Store.upsert(u)
                            afterSave(u)
                            editingId = null
                        },
                        onCancel = { editingId = null },
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            item {
                QuickRow(onAdd = { i ->
                    val u = i.copy(id = Store.nextId())
                    Store.upsert(u)
                    afterSave(u)
                })
            }
            item {
                Text(
                    "作者：ArchieKUO　v$version",
                    color = Gold, fontSize = 12.sp, fontStyle = FontStyle.Italic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
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
                }) { Text("刪除", color = ErrorRed) }
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
            .background(Color(0xFFFFF4D6))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, color = Color(0xFF5C4400), modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action, fontSize = 13.sp) }
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
    val textColor = if (item.enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.4f)
    val subColor = if (item.enabled) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.5f)
    val today = LocalDate.now()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck)
        Column(Modifier.width(88.dp)) {
            Text(item.timeText(), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = textColor)
            if (item.mask == 0 && item.dateEpochDay != null) {
                Text(Fmt.dayLabel(LocalDate.ofEpochDay(item.dateEpochDay), today), fontSize = 12.sp, color = subColor)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.label.ifBlank { "鬧鐘" }, fontSize = 16.sp, color = textColor,
                maxLines = 1,
            )
            Text(item.repeatText(), fontSize = 12.sp, color = subColor)
        }
        Switch(checked = item.enabled, onCheckedChange = onToggle)
    }
}

// ───────────────────────── 編輯列（只有這一列出現輸入欄） ─────────────────────────

@Composable
private fun EditRow(item: Item, onCommit: (Item) -> Unit, onCancel: () -> Unit) {
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
            .background(cs.primary.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
        timeError?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("每天", mask == 127, Modifier.weight(1f)) { mask = 127; dateError = null }
            Chip("工作日", mask == 31, Modifier.weight(1f)) { mask = 31; dateError = null }
            Chip("週末", mask == 96, Modifier.weight(1f)) { mask = 96; dateError = null }
            Chip("僅一次", mask == 0, Modifier.weight(1f)) { mask = 0 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Fmt.DOW.forEachIndexed { i, c ->
                val on = mask and (1 shl i) != 0
                Chip(c.toString(), on, Modifier.weight(1f)) { mask = mask xor (1 shl i) }
            }
        }

        if (mask == 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日期", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Cell(
                    value = date, onValueChange = { date = it; dateError = null },
                    modifier = Modifier.width(140.dp), fr = dateFr,
                    placeholder = "留空＝下一次", imeAction = ImeAction.Next,
                    isError = dateError != null,
                    onFocusChange = { if (it) snoozeActive = false },
                    onAction = { labelFr.requestFocus() },
                )
            }
        }
        dateError?.let { Text(it, color = ErrorRed, fontSize = 12.sp) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text(
                    if (snoozeActive) "稍後提醒 $snooze 分 ▾" else "稍後提醒 $snooze 分",
                    fontSize = 13.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            focus.clearFocus()
                            if (!snoozeActive) snoozeActive = true else menuOpen = true
                        }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
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
            TextButton(onClick = onCancel) { Text("取消", fontSize = 13.sp) }
            TextButton(onClick = { commit() }) { Text("確定", fontSize = 13.sp) }
        }
    }
}

// ───────────────────────── 最下面空白輸入列 ─────────────────────────

@Composable
private fun QuickRow(onAdd: (Item) -> Unit) {
    var tv by remember { mutableStateOf(TextFieldValue("")) }
    var err by remember { mutableStateOf<String?>(null) }

    fun tryAdd() {
        if (tv.text.isBlank()) {
            err = null
            return
        }
        val r = Parser.parseQuick(tv.text, LocalDateTime.now())
        if (r.item == null) {
            err = r.error
        } else {
            onAdd(r.item)
            tv = TextFieldValue("")
            err = null
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Cell(
            value = tv, onValueChange = { tv = it; err = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "＋ 輸入「週五 18:00 倒垃圾」按 Enter 新增",
            imeAction = ImeAction.Done,
            isError = err != null,
            onFocusChange = { if (!it) tryAdd() },
            onAction = { tryAdd() },
        )
        err?.let { Text(it, color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
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
        isError -> ErrorRed
        focused -> cs.primary
        else -> cs.outlineVariant
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
        textStyle = TextStyle(fontSize = 16.sp, color = if (isError) ErrorRed else cs.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text(placeholder, color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 15.sp, maxLines = 1)
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
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) cs.primary else Color.White)
            .border(1.dp, if (selected) cs.primary else cs.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) cs.onPrimary else cs.onSurface)
    }
}
