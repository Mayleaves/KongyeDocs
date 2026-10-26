package com.moji.notes.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun fmt(pattern: String, time: Long) = SimpleDateFormat(pattern, Locale.CHINA).format(Date(time))

private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private const val DAY = 24 * 60 * 60 * 1000L

/** 刚刚 / 5 分钟前 / 今天 14:20 / 昨天 09:12 / 10月3日 18:00 / 2024年5月1日 */
fun formatTime(time: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - time
    val today = dayStart(now)
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        time >= today -> "今天 " + fmt("HH:mm", time)
        time >= today - DAY -> "昨天 " + fmt("HH:mm", time)
        fmt("yyyy", time) == fmt("yyyy", now) -> fmt("M月d日 HH:mm", time)
        else -> fmt("yyyy年M月d日", time)
    }
}

fun formatFullTime(time: Long): String = fmt("yyyy年M月d日 HH:mm", time)

private fun sameYear(a: Long, b: Long) = fmt("yyyy", a) == fmt("yyyy", b)

/** 日期分组标题：09月21日；不是今年则为 2025年10月22日 */
fun dateHeader(time: Long, now: Long = System.currentTimeMillis()): String =
    if (sameYear(time, now)) fmt("MM月dd日", time) else fmt("yyyy年MM月dd日", time)

/** 列表里的时间：9月21日 18:20；不是今年则为 2025年10月22日 16:04 */
fun stampTime(time: Long, now: Long = System.currentTimeMillis()): String =
    if (sameYear(time, now)) fmt("M月d日 H:mm", time) else fmt("yyyy年M月d日 H:mm", time)

/** 最近列表的分组标题 */
fun timeGroup(time: Long, now: Long = System.currentTimeMillis()): String {
    val today = dayStart(now)
    return when {
        time >= today -> "今天"
        time >= today - DAY -> "昨天"
        time >= today - 6 * DAY -> "最近 7 天"
        time >= today - 29 * DAY -> "最近 30 天"
        else -> "更早"
    }
}

fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "无标题" }.take(60)

/** 文件大小：B / KB / MB */
fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
}

/** 从 first 那天到今天是第几天（按日历日算，当天是第 1 天） */
fun dayNumber(first: Long, now: Long = System.currentTimeMillis()): Int {
    fun dayStart(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return (Math.round((dayStart(now) - dayStart(first)) / 86_400_000.0) + 1).toInt().coerceAtLeast(1)
}
