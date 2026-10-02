package com.yarmiplaytv

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Platform logging: logcat on Android, stderr on desktop. */
object Logger {
    fun d(tag: String, message: String) = platformLog(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = platformLog(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = platformLog(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = platformLog(LogLevel.ERROR, tag, message, error)
}

internal expect fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?)
