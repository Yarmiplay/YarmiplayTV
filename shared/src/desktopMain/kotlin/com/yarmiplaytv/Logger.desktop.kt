package com.yarmiplaytv

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val debugEnabled = System.getProperty("yarmiplaytv.debug") == "true"
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

internal actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    if (level == LogLevel.DEBUG && !debugEnabled) return
    System.err.println("${LocalTime.now().format(timeFormat)} ${level.name.first()} $tag: $message")
    error?.printStackTrace()
}
