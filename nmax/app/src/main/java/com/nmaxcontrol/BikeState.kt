package com.nmaxcontrol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared state between the background service and the screen. */
object BikeState {
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val lastStream = HashMap<Int, String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var running: Boolean = false
    @Volatile var status: String = "Stopped"
    @Volatile var lastAction: String = "-"
    @Volatile var learnedKey: String? = null
    @Volatile var learnUntil: Long = 0L

    fun isLearning(): Boolean = System.currentTimeMillis() < learnUntil

    fun add(text: String) {
        synchronized(lock) {
            lines.addLast(fmt.format(Date()) + "  " + text)
            while (lines.size > 1500) lines.removeFirst()
        }
    }

    /**
     * The bike sends the "stream" characteristic ~3x per second. To avoid spam we only log a packet
     * when something other than the first 2 bytes (a counter) changed since the last packet of the same type.
     */
    fun addStream(bytes: ByteArray, hex: String) {
        val type = if (bytes.size > 5) bytes[5].toInt() and 0xFF else -1
        val body = if (bytes.size > 2) hex.split("-").drop(2).joinToString("-") else hex
        var changed = false
        synchronized(lock) {
            if (lastStream[type] != body) {
                lastStream[type] = body
                changed = true
            }
        }
        if (changed) add("[stream] $hex")
    }

    fun text(max: Int, newestFirst: Boolean): String = synchronized(lock) {
        val last = lines.toList().takeLast(max)
        (if (newestFirst) last.reversed() else last).joinToString("\n")
    }
}
