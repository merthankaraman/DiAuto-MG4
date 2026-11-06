package com.andrerinas.openheadunit.utils

internal object DiagnosticReportRedactor {
    private val secret = Regex("(?i)\\b(password|passphrase|psk|token|private.?key|payload|hex)\\b|\\bssid\\s*[=:]")
    // The whole colon-separated run: in "androidAutoMac:C8:..." a 6-pair match starts at "ac" and
    // left the address's last byte behind.
    private val mac = Regex("(?i)(?:[0-9a-f]{2}:){5,}[0-9a-f]{2}")
    private val ip = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    fun redact(line: String, ssid: String, password: String): String? {
        if (secret.containsMatchIn(line)) return null
        var value = line
        for (saved in listOf(ssid, password).filter { it.isNotEmpty() }) value = value.replace(saved, "[saved value]")
        return value.replace(mac, "[address]").replace(ip, "[ip]").replace(ipv6, "[ip]").take(1000)
    }
}
