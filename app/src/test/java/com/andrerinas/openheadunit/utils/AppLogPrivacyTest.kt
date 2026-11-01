package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLogPrivacyTest {

    @Test
    fun `network names are hidden, flags about them are not`() {
        assertEquals("  > Target SSID: [hidden]", AppLog.maskPrivate("  > Target SSID: SAIC_MG"))
        assertEquals("Credentials updated. SSID=[hidden], IP=192.168.33.1", AppLog.maskPrivate("Credentials updated. SSID=My_Home, IP=192.168.33.1"))
        assertEquals("pskLen=10 quotedSsid=false", AppLog.maskPrivate("pskLen=10 quotedSsid=false"))
    }

    @Test
    fun `names registered are hidden wherever they appear`() {
        AppLog.hide("Pixel de Test")
        assertEquals("Connection accepted from [hidden] (C8:A1:DC:XX:XX:XX)",
            AppLog.maskPrivate("Connection accepted from Pixel de Test (C8:A1:DC:A0:6D:8C)"))
    }

    @Test
    fun `too short a name is left alone`() {
        AppLog.hide("A")
        assertFalse(AppLog.maskPrivate("A line about Android").contains("[hidden]"))
    }
}
