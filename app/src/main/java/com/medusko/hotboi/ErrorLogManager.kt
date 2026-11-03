package com.medusko.hotboi

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ErrorRecord(
    val timestamp: String,
    val summary: String,
    val details: String,
    val stackTrace: String
)

object ErrorLogManager {

    private val errorHistory = mutableListOf<ErrorRecord>()
    var lastError: ErrorRecord? = null
        private set

    @Synchronized
    fun logError(summary: String, throwable: Throwable? = null) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val detailMsg = throwable?.localizedMessage ?: summary

        val stackTraceStr = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sw.toString()
        } else {
            "No stacktrace available"
        }

        val record = ErrorRecord(
            timestamp = ts,
            summary = summary,
            details = detailMsg,
            stackTrace = stackTraceStr
        )
        lastError = record
        errorHistory.add(0, record)

        if (errorHistory.size > 20) {
            errorHistory.removeAt(errorHistory.lastIndex)
        }
    }

    @Synchronized
    fun clearErrors() {
        lastError = null
        errorHistory.clear()
    }

    @Synchronized
    fun getHistory(): List<ErrorRecord> = ArrayList(errorHistory)
}
