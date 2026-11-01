package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.aap.AapNavigationHelper.NavigationSnapshot
import com.andrerinas.openheadunit.aap.AapNavigationHelper.TimedMessage
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationCurrentPosition
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationDistance
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationStepDistance
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDistanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaicNavigationMapperDistanceTest {

    private fun position(meters: Int, at: Long) = TimedMessage(
        NavigationCurrentPosition.newBuilder().setStepDistance(
            NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(meters))
        ).build(), at
    )

    private fun turnDistance(meters: Int, at: Long) =
        TimedMessage(NextTurnDistanceEvent.newBuilder().setDistanceMeters(meters).build(), at)

    @Test
    fun theDistanceComesFromTheCurrentPositionWhenThePhoneOnlySendsThat() {
        assertEquals(240, SaicNavigationMapper.distanceMeters(NavigationSnapshot(currentPosition = position(240, 10))))
    }

    @Test
    fun theDistanceFallsBackToTheOlderNextTurnDistance() {
        assertEquals(90, SaicNavigationMapper.distanceMeters(NavigationSnapshot(nextTurnDistance = turnDistance(90, 10))))
    }

    @Test
    fun theMostRecentOfTheTwoDistancesWins() {
        val newerPosition = NavigationSnapshot(nextTurnDistance = turnDistance(500, 10), currentPosition = position(120, 20))
        assertEquals(120, SaicNavigationMapper.distanceMeters(newerPosition))
        val newerEvent = NavigationSnapshot(nextTurnDistance = turnDistance(80, 30), currentPosition = position(120, 20))
        assertEquals(80, SaicNavigationMapper.distanceMeters(newerEvent))
    }

    @Test
    fun theCurrentPositionWinsATie() {
        val snapshot = NavigationSnapshot(nextTurnDistance = turnDistance(500, 20), currentPosition = position(120, 20))
        assertEquals(120, SaicNavigationMapper.distanceMeters(snapshot))
    }

    @Test
    fun noDistanceAtAllIsUnknownRatherThanZero() {
        assertNull(SaicNavigationMapper.distanceMeters(NavigationSnapshot()))
    }
}
