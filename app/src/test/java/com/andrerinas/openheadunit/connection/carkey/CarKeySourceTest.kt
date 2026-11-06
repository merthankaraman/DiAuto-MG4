package com.andrerinas.openheadunit.connection.carkey

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeySourceTest {

    @Test
    fun `radio, Bluetooth, USB and CarPlay keep the media keys`() {
        listOf(1, 2, 3, 5, 6, 7, 50).forEach { assertTrue(CarKeyBroadcastReceiver.isOtherSource(it)) }
    }

    @Test
    fun `Android Auto or no known source leaves them to Android Auto`() {
        listOf(70, 0, -1, null).forEach { assertFalse(CarKeyBroadcastReceiver.isOtherSource(it)) }
    }
}
