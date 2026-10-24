package com.chaman.autoclaim

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buf = StringBuilder()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg\n"
        synchronized(buf) {
            buf.append(line)
            if (buf.length > 30000) buf.delete(0, 10000)
        }
        Log.d("AutoClaim", msg)
        listener?.invoke(line)
    }

    fun all(): String = synchronized(buf) { buf.toString() }

    fun clear() = synchronized(buf) { buf.setLength(0) }
}
