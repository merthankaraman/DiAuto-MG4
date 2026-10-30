package com.andrerinas.openheadunit.aap

/**
 * Picks the BSSID to advertise for our own access point, preferring its live interface address.
 *
 * A chain rather than one source because `NetworkInterface.getHardwareAddress()` returns null or a
 * placeholder on plenty of devices since Android 6.0, while `/sys/class/net/<iface>/address` still
 * works on most head units. Pure and tested: case, placeholder detection and empty-vs-null are
 * easy to get subtly wrong and impossible to check by reading a log.
 */
object SoftApBssidPolicy {

    /**
     * Android's masking placeholders. Note what is *not* here: anything merely beginning with
     * `02:`. That bit marks a locally-administered MAC and Android's own soft AP routinely uses a
     * randomised one, so rejecting the range would discard the BSSID this exists to find.
     */
    private val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /** Six hex pairs separated by colons or dashes. */
    private val MAC_SHAPE = Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$")

    /**
     * The first usable live interface address, falling back to [staticOverride] only when the
     * current AP address cannot be read. What to do about "" differs by transport — see
     * [NativeCredentialsPolicy].
     */
    fun choose(staticOverride: String?, shellMac: String?, hardwareAddress: String?, ipv6DerivedMac: String? = null): String =
        listOf(shellMac, hardwareAddress, ipv6DerivedMac, staticOverride)
            .firstOrNull { isUsable(it) }
            ?.let { normalise(it) }
            ?: ""

    /**
     * Whether [mac] is a real address.
     *
     * Checks the shape, not only a list of known non-addresses. The setting stores the string "0"
     * when unset, and a free-text field can produce other non-addresses, so validate what an
     * address looks like before using it as a fallback.
     */
    fun isUsable(mac: String?): Boolean {
        val trimmed = mac?.trim().orEmpty()
        if (!MAC_SHAPE.matches(trimmed)) return false
        return normalise(trimmed).lowercase() !in PLACEHOLDERS
    }

    fun sameAddress(first: String?, second: String?): Boolean {
        if (!isUsable(first) || !isUsable(second)) return false
        return normalise(first.orEmpty()) == normalise(second.orEmpty())
    }

    /** Dashes to colons, upper case. Accepts either separator so a hand-typed address still works. */
    private fun normalise(mac: String): String = mac.trim().replace('-', ':').uppercase()
}
