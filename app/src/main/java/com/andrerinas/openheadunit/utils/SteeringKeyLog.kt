package com.andrerinas.openheadunit.utils

/** The last steering wheel key codes the car reported, for the compatibility report. */
object SteeringKeyLog {
    private const val SIZE = 10
    private val entries = ArrayDeque<String>()

    @Synchronized
    fun record(code: Int, down: Boolean, long: Boolean) {
        if (!down && !long) return // one entry per press: the press, or the long press
        entries.addLast(if (long) "$code(long)" else "$code")
        while (entries.size > SIZE) entries.removeFirst()
    }

    @Synchronized
    fun recent(): List<String> = entries.toList()
}
