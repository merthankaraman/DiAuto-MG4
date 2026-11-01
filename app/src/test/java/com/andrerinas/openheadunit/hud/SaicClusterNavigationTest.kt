package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail
import com.andrerinas.openheadunit.aap.AapNavigationHelper.NavigationSnapshot
import com.andrerinas.openheadunit.aap.AapNavigationHelper.TimedMessage
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationCurrentPosition
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationDistance
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationStepDistance
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDistanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaicClusterNavigationTest {

    private fun maneuver(type: NavigationType, angle: Int? = null) =
        NavigationManeuver.newBuilder().setType(type).apply { angle?.let { setRoundaboutExitAngle(it) } }.build()

    private fun detail(event: NextTurnDetail.NextEvent, side: NextTurnDetail.Side) =
        NextTurnDetail.newBuilder().setRoad("A1").setNextTurn(event).setSide(side).build()

    @Test
    fun `turns map onto the cluster's left and right arrows`() {
        assertEquals(1, SaicClusterNavigation.iconFor(maneuver(NavigationType.TURN_NORMAL_LEFT)))
        assertEquals(2, SaicClusterNavigation.iconFor(maneuver(NavigationType.TURN_NORMAL_RIGHT)))
        assertEquals(3, SaicClusterNavigation.iconFor(maneuver(NavigationType.TURN_SLIGHT_LEFT)))
        assertEquals(7, SaicClusterNavigation.iconFor(maneuver(NavigationType.TURN_SHARP_RIGHT)))
        assertEquals(35, SaicClusterNavigation.iconFor(maneuver(NavigationType.OFF_RAMP_NORMAL_RIGHT)))
        assertEquals(22, SaicClusterNavigation.iconFor(maneuver(NavigationType.DESTINATION_RIGHT)))
        assertEquals(0, SaicClusterNavigation.iconFor(maneuver(NavigationType.STRAIGHT)))
    }

    @Test
    fun `clockwise roundabout exits follow the angle from the entrance`() {
        assertEquals(10, SaicClusterNavigation.iconFor(maneuver(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 90)))
        assertEquals(9, SaicClusterNavigation.iconFor(maneuver(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 180)))
        assertEquals(11, SaicClusterNavigation.iconFor(maneuver(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 270)))
        assertEquals(12, SaicClusterNavigation.iconFor(maneuver(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, 360)))
        assertEquals(9, SaicClusterNavigation.iconFor(maneuver(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW)))
    }

    @Test
    fun `anticlockwise roundabouts mirror left and right`() {
        assertEquals(11, SaicClusterNavigation.roundaboutIcon(90, clockwise = false))
        assertEquals(10, SaicClusterNavigation.roundaboutIcon(270, clockwise = false))
    }

    @Test
    fun `older next-turn detail still maps by side`() {
        assertEquals(1, SaicClusterNavigation.iconFor(detail(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT)))
        assertEquals(18, SaicClusterNavigation.iconFor(detail(NextTurnDetail.NextEvent.FORK, NextTurnDetail.Side.RIGHT)))
        assertEquals(20, SaicClusterNavigation.iconFor(detail(NextTurnDetail.NextEvent.DESTINATION, NextTurnDetail.Side.UNSPECIFIED)))
        assertEquals(0, SaicClusterNavigation.iconFor(null))
    }

    private fun position(metres: Int, at: Long) = TimedMessage(
        NavigationCurrentPosition.newBuilder().setStepDistance(
            NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder().setMeters(metres))
        ).build(), at
    )

    private fun turnDistance(metres: Int, at: Long) =
        TimedMessage(NextTurnDistanceEvent.newBuilder().setDistanceMeters(metres).build(), at)

    @Test
    fun `distance comes from the current position when the phone only sends that`() {
        assertEquals(240, SaicClusterNavigation.distanceMetres(NavigationSnapshot(currentPosition = position(240, 10))))
    }

    @Test
    fun `distance falls back to the older next-turn distance`() {
        assertEquals(90, SaicClusterNavigation.distanceMetres(NavigationSnapshot(nextTurnDistance = turnDistance(90, 10))))
    }

    @Test
    fun `the most recent of the two distances wins`() {
        val snapshot = NavigationSnapshot(nextTurnDistance = turnDistance(500, 10), currentPosition = position(120, 20))
        assertEquals(120, SaicClusterNavigation.distanceMetres(snapshot))
    }

    @Test
    fun `no distance at all is unknown rather than zero`() {
        assertNull(SaicClusterNavigation.distanceMetres(NavigationSnapshot()))
    }
}
