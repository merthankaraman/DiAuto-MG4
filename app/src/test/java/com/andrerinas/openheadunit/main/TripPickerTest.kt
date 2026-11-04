package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripPickerTest {

    @Test
    fun `the default is 20 points below the current charge`() {
        assertEquals(32, TripPicker.defaultForecast(52))
    }

    @Test
    fun `the default keeps at least 10 points and stays above zero`() {
        assertEquals(0, TripPicker.defaultForecast(15))
        assertEquals(0, TripPicker.defaultForecast(10))
    }

    @Test
    fun `a forecast less than 10 points below the current charge is refused`() {
        assertEquals(42, TripPicker.maxForecast(52))
        assertEquals(42, TripPicker.step(40, 10, 42))
        assertEquals(41, TripPicker.step(40, 1, 42))
        assertEquals(0, TripPicker.step(5, -10, 42))
    }

    @Test
    fun `below 10 percent nothing can be recorded`() {
        assertNull(TripPicker.maxForecast(9))
        assertNull(TripPicker.defaultForecast(9))
    }
}
