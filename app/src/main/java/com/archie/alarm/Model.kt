package com.archie.alarm

import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** mask: bit0=週一 ... bit6=週日；mask==0 代表僅一次（可指定日期） */
data class Item(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val mask: Int = 0,
    val dateEpochDay: Long? = null,
    val enabled: Boolean = true,
    val snoozeMin: Int = 10,
    val note: String = "",
    /** 提前幾分鐘提醒；0 代表不提前 */
    val advanceMin: Int = 0,
    /** true＝提前那次也像鬧鐘一樣響 */
    val advanceImportant: Boolean = false,
) {
    fun timeText(): String = "%02d:%02d".format(hour, minute)

    fun repeatText(): String = when {
        mask == 127 -> "每天"
        mask == 31 -> "工作日"
        mask == 96 -> "週末"
        mask == 0 -> "僅一次"
        else -> "週" + (0..6).filter { mask and (1 shl it) != 0 }
            .joinToString(" ") { Fmt.DOW[it].toString() }
    }

    fun nextTrigger(now: LocalDateTime): LocalDateTime? {
        val t = LocalTime.of(hour, minute)
        if (mask == 0) {
            return if (dateEpochDay != null) {
                val dt = LocalDateTime.of(LocalDate.ofEpochDay(dateEpochDay), t)
                if (dt.isAfter(now)) dt else null
            } else {
                val today = LocalDateTime.of(now.toLocalDate(), t)
                if (today.isAfter(now)) today else today.plusDays(1)
            }
        }
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val bit = d.dayOfWeek.value - 1
            if (mask and (1 shl bit) != 0) {
                val dt = LocalDateTime.of(d, t)
                if (dt.isAfter(now)) return dt
            }
        }
        return null
    }

    fun nextEpoch(now: LocalDateTime): Long? =
        nextTrigger(now)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    /** 下一次「提前提醒」的時間；沒設提前，或提前的時間已經過了就回傳 null */
    fun nextAdvance(now: LocalDateTime): LocalDateTime? {
        if (advanceMin <= 0) return null
        val t = nextTrigger(now) ?: return null
        val a = t.minusMinutes(advanceMin.toLong())
        return if (a.isAfter(now)) a else null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("h", hour)
        put("m", minute)
        put("label", label)
        put("mask", mask)
        put("date", dateEpochDay ?: JSONObject.NULL)
        put("on", enabled)
        put("snooze", snoozeMin)
        put("note", note)
        put("adv", advanceMin)
        put("advImp", advanceImportant)
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.getLong("id"),
            hour = o.getInt("h"),
            minute = o.getInt("m"),
            label = o.optString("label", ""),
            mask = o.optInt("mask", 0),
            dateEpochDay = if (o.has("date") && !o.isNull("date")) o.getLong("date") else null,
            enabled = o.optBoolean("on", true),
            snoozeMin = o.optInt("snooze", 10),
            note = o.optString("note", ""),
            advanceMin = o.optInt("adv", 0),
            advanceImportant = o.optBoolean("advImp", false),
        )
    }
}

object Fmt {
    const val DOW = "一二三四五六日"

    val ADVANCE_CHOICES = listOf(0, 5, 15, 30, 60, 180, 1440, 4320)

    fun dow(d: LocalDate): Char = DOW[d.dayOfWeek.value - 1]

    fun dayLabel(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${d.monthValue}/${d.dayOfMonth}(週${dow(d)})"
    }

    private fun minutesText(m: Long): String = when {
        m < 1 -> "不到 1 分鐘後"
        m < 60 -> "$m 分鐘後"
        m < 1440 -> "${m / 60} 小時 ${m % 60} 分後"
        else -> "${m / 1440} 天 ${(m % 1440) / 60} 小時後"
    }

    fun until(now: LocalDateTime, t: LocalDateTime): String =
        minutesText(Duration.between(now, t).toMinutes().coerceAtLeast(0))

    fun untilMillis(now: Long, at: Long): String = minutesText(((at - now) / 60000L).coerceAtLeast(0))

    /** 下拉選單用：30 分鐘前、1 小時前、1 天前 */
    fun advBefore(m: Int): String = when {
        m <= 0 -> "不提前"
        m < 60 -> "$m 分鐘前"
        m < 1440 -> "${m / 60} 小時前"
        else -> "${m / 1440} 天前"
    }

    /** 標籤用：30 分、1 小時、1 天 */
    fun advShort(m: Int): String = when {
        m < 60 -> "$m 分"
        m < 1440 -> "${m / 60} 小時"
        else -> "${m / 1440} 天"
    }
}
