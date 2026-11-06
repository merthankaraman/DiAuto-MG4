package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCredentialsPolicyTest {

    @Test
    fun `a real address is usable and a masked or missing one is not`() {
        assertTrue(NativeCredentialsPolicy.isUsableBssid("AA:BB:CC:DD:EE:FF"))
        assertTrue(NativeCredentialsPolicy.isUsableBssid("02:1a:2b:3c:4d:5e"))

        assertFalse(NativeCredentialsPolicy.isUsableBssid(null))
        assertFalse(NativeCredentialsPolicy.isUsableBssid(""))
        assertFalse(NativeCredentialsPolicy.isUsableBssid("00:00:00:00:00:00"))
        assertFalse(NativeCredentialsPolicy.isUsableBssid("02:00:00:00:00:00"))
    }

    @Test
    fun `an interface we guessed at is not advertised when no access point is running`() {
        // Measured on a Unisoc unit: with the real AP down, the cellular bridge seth_lte0 was the
        // only interface up with a private address, so the right network name went out paired with
        // an address no phone could reach — and the log said SUCCESS.
        assertFalse(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.NOT_ENABLED, false))
    }

    @Test
    fun `naming the interface overrides the state read`() {
        // The escape hatch for a vendor that starts hostapd outside the framework, where the state
        // read is silent while the access point is plainly there.
        assertTrue(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.NOT_ENABLED, true))
    }

    @Test
    fun `not being able to ask is never treated as an answer`() {
        // getWifiApState is not public API and is blocked outright on some devices. Refusing there
        // would break the route on every one of them.
        assertTrue(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.UNKNOWN, false))
        assertTrue(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.UNKNOWN, true))
    }

    @Test
    fun `a running access point publishes however the interface was chosen`() {
        assertTrue(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.ENABLED, false))
        assertTrue(NativeCredentialsPolicy.shouldPublishCredentials(SoftApState.ENABLED, true))
    }
}
