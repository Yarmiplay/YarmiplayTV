package com.yarmiplaytv

import android.util.Log

internal actual fun platformLog(level: LogLevel, tag: String, message: String, error: Throwable?) {
    when (level) {
        LogLevel.DEBUG -> Log.d(tag, message, error)
        LogLevel.INFO -> Log.i(tag, message, error)
        LogLevel.WARN -> Log.w(tag, message, error)
        LogLevel.ERROR -> Log.e(tag, message, error)
    }
}
