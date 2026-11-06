package com.andrerinas.openheadunit.utils

import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportRedactorTest {
    @Test fun removesCredentialsAndRetainsUsefulFailureDetails() {
        assertNull(DiagnosticReportRedactor.redact("SSID=car, password=secret", "car", "secret"))
        assertNull(DiagnosticReportRedactor.redact("payload=010203", "", ""))
        assertEquals("NativeAA: No usable BSSID; join failure status=-3", DiagnosticReportRedactor.redact(
            "NativeAA: No usable BSSID; join failure status=-3", "", ""))
        assertEquals("Network [saved value] at [address] [ip]", DiagnosticReportRedactor.redact(
            "Network My Car at 4e:12:34:56:78:9a 192.168.46.1", "My Car", "secret"))
    }
}
