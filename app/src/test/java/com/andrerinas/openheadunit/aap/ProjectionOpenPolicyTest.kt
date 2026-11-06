package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionOpenPolicyTest {

    @Test
    fun `the setting on always opens`() =
        assertTrue(ProjectionOpenPolicy.shouldOpen(settingOn = true, projectionInFront = false, homeInFront = false))

    @Test
    fun `the setting off stays in the background`() =
        assertFalse(ProjectionOpenPolicy.shouldOpen(settingOn = false, projectionInFront = false, homeInFront = false))

    @Test
    fun `a reconnect with Android Auto on screen stays there`() =
        assertTrue(ProjectionOpenPolicy.shouldOpen(settingOn = false, projectionInFront = true, homeInFront = false))

    @Test
    fun `the driver waiting on DiAuto's home screen sees it open`() =
        assertTrue(ProjectionOpenPolicy.shouldOpen(settingOn = false, projectionInFront = false, homeInFront = true))
}
