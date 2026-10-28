package com.archie.alarm

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class Quick(val item: Item?, val error: String? = null)

object Parser {
    class TimeHit(val h: Int, val m: Int, val range: IntRange)

    private const val DOW_CHARS = "一二三四五六日天"

    private val cnTime = Regex("(上午|早上|凌晨|中午|下午|晚上)?\\s*(\\d{1,2})\\s*(?::(\\d{2})|[點点時时]\\s*(半|\\d{1,2})?\\s*分?)")
    private val enTime = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", RegexOption.IGNORE_CASE)

    private val cnNumRun = Regex(
        "[零〇一二兩三四五六七八九十]+(?=[點点時时分月日號号])|(?<=[點点時时])[零〇一二兩三四五六七八九十]+"
    )

    private fun cnDigit(c: Char): Int = when (c) {
        '零', '〇' -> 0; '一' -> 1; '二', '兩' -> 2; '三' -> 3; '四' -> 4
        '五' -> 5; '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9; else -> 0
    }

    private fun cnToInt(s: String): Int {
        val i = s.indexOf('十')
        if (i >= 0) {
            val tens = if (i == 0) 1 else cnDigit(s[0])
            val ones = if (i == s.length - 1) 0 else cnDigit(s[i + 1])
            return tens * 10 + ones
        }
        var v = 0
        for (c in s) v = v * 10 + cnDigit(c)
        return v
    }

    /** 語音常回「下午三點二十分」：只轉換時間、日期周圍的中文數字，內容文字不動 */
    private fun cnNumbers(s: String): String =
        cnNumRun.replace(s) { cnToInt(it.value).toString() }

    private fun norm(input: String): String {
        val s = cnNumbers(input)
        val sb = StringBuilder()
        for (c in s) {
            sb.append(
                when {
                    c in '０'..'９' -> '0' + (c - '０')
                    c == '：' -> ':'
                    c == '／' -> '/'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun dowIndex(c: Char): Int {
        val i = DOW_CHARS.indexOf(c)
        return if (i == 7) 6 else i
    }

    fun findTime(s: String): TimeHit? {
        enTime.find(s)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val pm = m.groupValues[3].lowercase() == "pm"
            if (h !in 1..12 || min > 59) return null
            if (pm && h < 12) h += 12
            if (!pm && h == 12) h = 0
            return TimeHit(h, min, m.range)
        }
        cnTime.find(s)?.let { m ->
            val period = m.groupValues[1]
            var h = m.groupValues[2].toInt()
            val min = when {
                m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                m.groupValues[4] == "半" -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                else -> 0
            }
            when (period) {
                "下午", "晚上" -> if (h < 12) h += 12
                "上午", "早上", "凌晨" -> if (h == 12) h = 0
                "中午" -> if (h < 11) h += 12
            }
            if (h > 23 || min > 59) return null
            return TimeHit(h, min, m.range)
        }
        return null
    }

    /** 時間欄位用：07:30、0730、730、7、7點30、下午3點 */
    fun parseTime(input: String): Pair<Int, Int>? {
        val s = norm(input).trim()
        if (s.isEmpty()) return null
        findTime(s)?.let { return it.h to it.m }
        if (Regex("\\d{1,4}").matches(s)) {
            val v = s.toInt()
            val h: Int
            val m: Int
            when (s.length) {
                1, 2 -> { h = v; m = 0 }
                else -> { h = v / 100; m = v % 100 }
            }
            if (h in 0..23 && m in 0..59) return h to m
        }
        return null
    }

    private fun dateOf(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (_: DateTimeException) { null }

    /** 日期欄位用：今天、明天、後天、週五、10/28、10月28日、2026/10/28 */
    fun parseDate(input: String, today: LocalDate): LocalDate? {
        val s = norm(input).trim()
        when {
            s == "今天" || s == "今日" -> return today
            s == "明天" || s == "明日" -> return today.plusDays(1)
            s == "後天" -> return today.plusDays(2)
            s == "大後天" -> return today.plusDays(3)
        }
        Regex("^(?:週|周|星期|禮拜)([一二三四五六日天])$").find(s)?.let {
            val i = dowIndex(it.groupValues[1][0])
            var d = today
            while (d.dayOfWeek.value - 1 != i) d = d.plusDays(1)
            return d
        }
        Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$").find(s)?.let {
            return dateOf(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        val md = Regex("^(\\d{1,2})/(\\d{1,2})$").find(s)
            ?: Regex("^(\\d{1,2})月(\\d{1,2})[日號号]?$").find(s)
        if (md != null) {
            val c = dateOf(today.year, md.groupValues[1].toInt(), md.groupValues[2].toInt()) ?: return null
            return if (c.isBefore(today)) c.plusYears(1) else c
        }
        return null
    }

    fun formatDate(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${d.monthValue}/${d.dayOfMonth}"
    }

    /** 空白列用：「週五 18:00 倒垃圾」「每天 7點 起床」「10/28 下午3點半 開會」 */
    fun parseQuick(input: String, now: LocalDateTime): Quick {
        var s = norm(input).trim()
        val t = findTime(s) ?: return Quick(null, "找不到時間，試試「明天 15:00 開會」")
        s = s.removeRange(t.range)
        val today = now.toLocalDate()
        val tod = LocalTime.of(t.h, t.m)
        var mask = 0
        var date: LocalDate? = null

        fun grab(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = s.removeRange(m.range)
            return m
        }

        if (grab(Regex("每(?:天|日)")) != null) {
            mask = 127
        } else if (grab(Regex("工作日|平日")) != null) {
            mask = 31
        } else if (grab(Regex("週末|周末")) != null) {
            mask = 96
        } else {
            val m = grab(Regex("每(?:週|周|星期|禮拜)([一二三四五六日天、,\\s]+)"))
            if (m != null) {
                for (c in m.groupValues[1]) {
                    val i = dowIndex(c)
                    if (i >= 0) mask = mask or (1 shl i)
                }
            }
        }

        if (mask == 0) {
            val w = grab(Regex("(?:週|周|星期|禮拜)([一二三四五六日天])"))
            if (w != null) {
                val i = dowIndex(w.groupValues[1][0])
                var d = today
                while (d.dayOfWeek.value - 1 != i || !LocalDateTime.of(d, tod).isAfter(now)) d = d.plusDays(1)
                date = d
            } else if (grab(Regex("大後天")) != null) {
                date = today.plusDays(3)
            } else if (grab(Regex("後天")) != null) {
                date = today.plusDays(2)
            } else if (grab(Regex("明天|明日")) != null) {
                date = today.plusDays(1)
            } else if (grab(Regex("今天|今日")) != null) {
                date = today
            } else {
                val ymd = grab(Regex("(\\d{4})/(\\d{1,2})/(\\d{1,2})"))
                if (ymd != null) {
                    date = dateOf(ymd.groupValues[1].toInt(), ymd.groupValues[2].toInt(), ymd.groupValues[3].toInt())
                        ?: return Quick(null, "日期不存在，請檢查月份和日")
                } else {
                    val md = grab(Regex("(\\d{1,2})/(\\d{1,2})")) ?: grab(Regex("(\\d{1,2})月(\\d{1,2})[日號号]?"))
                    if (md != null) {
                        val c = dateOf(today.year, md.groupValues[1].toInt(), md.groupValues[2].toInt())
                            ?: return Quick(null, "日期不存在，請檢查月份和日")
                        date = if (c.isBefore(today)) c.plusYears(1) else c
                    }
                }
            }
            if (date != null && !LocalDateTime.of(date, tod).isAfter(now)) {
                return Quick(null, "這個時間已經過了，換個時間或日期")
            }
        }

        s = Regex("^(?:提醒我|提醒|叫我)").replace(s.trim(), "")
        val label = s.trim { it.isWhitespace() || it in ",，、:：-" }
        return Quick(
            Item(
                id = 0, hour = t.h, minute = t.m, label = label, mask = mask,
                dateEpochDay = date?.toEpochDay(), enabled = true, snoozeMin = 10
            )
        )
    }
}
