package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side
import com.andrerinas.openheadunit.aap.AapNavigationHelper.NavigationSnapshot
import com.andrerinas.openheadunit.aap.AapNavigationHelper.TimedMessage
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDistanceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaicNavigationOutputsTest {

    private fun frame(
        icon: ClusterIdTelenav = ClusterIdTelenav.TURN_LEFT,
        distance: Int = 300,
        road: String = "Rue Victor Hugo",
        remainingMeters: Int = 5000,
        remainingSeconds: Int = 600,
    ) = SaicGuidance(icon, distance, road, remainingMeters, remainingSeconds)

    @Test
    fun theIconIdsAreTheOnesTheClusterExpects() {
        assertEquals(0, ClusterIdTelenav.CONTINUE.id)
        assertEquals(1, ClusterIdTelenav.TURN_LEFT.id)
        assertEquals(7, ClusterIdTelenav.TURN_HARD_RIGHT.id)
        assertEquals(9, ClusterIdTelenav.ROUND_ABOUT_3.id)
        assertEquals(17, ClusterIdTelenav.STAY_LEFT.id)
        assertEquals(32, ClusterIdTelenav.ENTER_RIGHT.id)
        assertEquals(36, ClusterIdTelenav.EXIT_LEFT.id)
        assertEquals(39, ClusterIdTelenav.HOOK_TURN_RIGHT.id)
        assertEquals((0..39).toList(), ClusterIdTelenav.values().map { it.id })
    }

    @Test
    fun turnsMapToTheTurnIcons() {
        assertEquals(ClusterIdTelenav.TURN_LEFT, SaicNavigationMapper.icon(NavigationType.TURN_NORMAL_LEFT))
        assertEquals(ClusterIdTelenav.TURN_RIGHT, SaicNavigationMapper.icon(NavigationType.TURN_NORMAL_RIGHT))
        assertEquals(ClusterIdTelenav.TURN_HARD_LEFT, SaicNavigationMapper.icon(NavigationType.TURN_SHARP_LEFT))
        assertEquals(ClusterIdTelenav.TURN_HARD_RIGHT, SaicNavigationMapper.icon(NavigationType.TURN_SHARP_RIGHT))
        assertEquals(ClusterIdTelenav.LEFT_U_TURN, SaicNavigationMapper.icon(NavigationType.U_TURN_LEFT))
        assertEquals(ClusterIdTelenav.RIGHT_U_TURN, SaicNavigationMapper.icon(NavigationType.U_TURN_RIGHT))
    }

    @Test
    fun slightTurnsUseTheSlideIconsIds17And18() {
        assertEquals(17, SaicNavigationMapper.icon(NavigationType.TURN_SLIGHT_LEFT)?.id)
        assertEquals(18, SaicNavigationMapper.icon(NavigationType.TURN_SLIGHT_RIGHT)?.id)
    }

    @Test
    fun forksUseTheBearIconsIds3And4() {
        assertEquals(3, SaicNavigationMapper.icon(NavigationType.FORK_LEFT)?.id)
        assertEquals(4, SaicNavigationMapper.icon(NavigationType.FORK_RIGHT)?.id)
    }

    @Test
    fun keepsUseTheSlideIconsIds17And18() {
        assertEquals(17, SaicNavigationMapper.icon(NavigationType.KEEP_LEFT)?.id)
        assertEquals(18, SaicNavigationMapper.icon(NavigationType.KEEP_RIGHT)?.id)
    }

    @Test
    fun aRoundaboutExitIsASlightTurnToTheSideOfTheTraffic() {
        assertEquals(4, SaicNavigationMapper.icon(NavigationType.ROUNDABOUT_EXIT, rightHandDrive = false)?.id)
        assertEquals(3, SaicNavigationMapper.icon(NavigationType.ROUNDABOUT_EXIT, rightHandDrive = true)?.id)
    }

    @Test
    fun goingStraightUnknownAndDepartMapToContinue() {
        for (type in listOf(NavigationType.UNKNOWN, NavigationType.DEPART, NavigationType.NAME_CHANGE, NavigationType.STRAIGHT)) {
            assertEquals("$type", ClusterIdTelenav.CONTINUE, SaicNavigationMapper.icon(type))
        }
    }

    @Test
    fun rampsKeepTheIconOfTheirShape() {
        assertEquals(ClusterIdTelenav.TURN_LEFT, SaicNavigationMapper.icon(NavigationType.ON_RAMP_NORMAL_LEFT))
        assertEquals(ClusterIdTelenav.TURN_RIGHT, SaicNavigationMapper.icon(NavigationType.ON_RAMP_NORMAL_RIGHT))
        assertEquals(17, SaicNavigationMapper.icon(NavigationType.ON_RAMP_SLIGHT_LEFT)?.id)
        assertEquals(18, SaicNavigationMapper.icon(NavigationType.ON_RAMP_SLIGHT_RIGHT)?.id)
        assertEquals(ClusterIdTelenav.TURN_HARD_LEFT, SaicNavigationMapper.icon(NavigationType.ON_RAMP_SHARP_LEFT))
        assertEquals(ClusterIdTelenav.TURN_HARD_RIGHT, SaicNavigationMapper.icon(NavigationType.ON_RAMP_SHARP_RIGHT))
        assertEquals(ClusterIdTelenav.LEFT_U_TURN, SaicNavigationMapper.icon(NavigationType.ON_RAMP_U_TURN_LEFT))
        assertEquals(ClusterIdTelenav.RIGHT_U_TURN, SaicNavigationMapper.icon(NavigationType.ON_RAMP_U_TURN_RIGHT))
        assertEquals(17, SaicNavigationMapper.icon(NavigationType.OFF_RAMP_SLIGHT_LEFT)?.id)
        assertEquals(18, SaicNavigationMapper.icon(NavigationType.OFF_RAMP_SLIGHT_RIGHT)?.id)
    }

    @Test
    fun exitsAndMergesUseTheExitIcons() {
        assertEquals(ClusterIdTelenav.EXIT_LEFT, SaicNavigationMapper.icon(NavigationType.OFF_RAMP_NORMAL_LEFT))
        assertEquals(ClusterIdTelenav.EXIT_RIGHT, SaicNavigationMapper.icon(NavigationType.OFF_RAMP_NORMAL_RIGHT))
        assertEquals(ClusterIdTelenav.EXIT_LEFT, SaicNavigationMapper.icon(NavigationType.MERGE_LEFT))
        assertEquals(ClusterIdTelenav.EXIT_RIGHT, SaicNavigationMapper.icon(NavigationType.MERGE_RIGHT))
    }

    private val ccw = SaicNavigationMapper.Circulation.COUNTER_CLOCKWISE
    private val cw = SaicNavigationMapper.Circulation.CLOCKWISE

    @Test
    fun counterClockwiseExitAnglesGiveTheExitPositionSeenFromTheEntry() {
        // Right-hand traffic: four evenly spaced exits are right, straight on, left, back.
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, SaicNavigationMapper.roundaboutIcon(ccw, null, 90))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(ccw, null, 180))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.roundaboutIcon(ccw, null, 270))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_7, SaicNavigationMapper.roundaboutIcon(ccw, null, 360))
    }

    @Test
    fun clockwiseExitAnglesAreTheMirrorImage() {
        // Left-hand traffic: 90 degrees round clockwise is the exit on the left.
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.roundaboutIcon(cw, null, 90))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(cw, null, 180))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, SaicNavigationMapper.roundaboutIcon(cw, null, 270))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_7, SaicNavigationMapper.roundaboutIcon(cw, null, 360))
    }

    @Test
    fun anglesBetweenTheClockPositionsTakeTheNearestIcon() {
        // 150 degrees counter-clockwise: heading 30, the 1 o'clock icon. 210: heading 330, 11 o'clock.
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_2, SaicNavigationMapper.roundaboutIcon(ccw, null, 150))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_4, SaicNavigationMapper.roundaboutIcon(ccw, null, 210))
        // 30 degrees: heading 150, the 5 o'clock icon, a sharp exit back to the right.
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_0, SaicNavigationMapper.roundaboutIcon(ccw, null, 30))
    }

    @Test
    fun theExitNumberIsEstimatedWhenThereIsNoAngle() {
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, SaicNavigationMapper.roundaboutIcon(ccw, 1, null))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(ccw, 2, null))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.roundaboutIcon(ccw, 3, null))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.roundaboutIcon(ccw, 5, null))
    }

    @Test
    fun theAngleWinsOverTheExitNumber() {
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(ccw, 1, 180))
    }

    @Test
    fun withoutAnyExitInformationARoundaboutShowsStraightThrough() {
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(ccw, null, null))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, SaicNavigationMapper.roundaboutIcon(cw, 0, 0))
    }

    @Test
    fun aRoundaboutEntryWithNoExitHasNoIcon() {
        assertNull(SaicNavigationMapper.icon(NavigationType.ROUNDABOUT_ENTER))
    }

    @Test
    fun aMergeWithNoSideContinuesStraight() {
        assertEquals(0, SaicNavigationMapper.icon(NavigationType.MERGE_SIDE_UNSPECIFIED)?.id)
    }

    @Test
    fun theRoundaboutManeuverTypesPickTheirCirculation() {
        val ccwType = NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
        val cwType = NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, SaicNavigationMapper.icon(ccwType, exitAngle = 90))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.icon(cwType, exitAngle = 90))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, SaicNavigationMapper.icon(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, exitNumber = 1))
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, SaicNavigationMapper.icon(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW, exitNumber = 1))
    }

    @Test
    fun legacyTurnEventsBecomeManeuverTypesBySide() {
        assertEquals(NavigationType.TURN_NORMAL_LEFT, SaicNavigationMapper.legacyType(NextEvent.TURN, Side.LEFT))
        assertEquals(NavigationType.TURN_SHARP_RIGHT, SaicNavigationMapper.legacyType(NextEvent.SHARP_TURN, Side.RIGHT))
        assertEquals(NavigationType.TURN_SLIGHT_LEFT, SaicNavigationMapper.legacyType(NextEvent.SLIGHT_TURN, Side.LEFT))
        assertEquals(NavigationType.U_TURN_RIGHT, SaicNavigationMapper.legacyType(NextEvent.U_TURN, Side.RIGHT))
        assertEquals(NavigationType.ON_RAMP_NORMAL_RIGHT, SaicNavigationMapper.legacyType(NextEvent.ON_RAMP, Side.RIGHT))
        assertEquals(NavigationType.OFF_RAMP_NORMAL_LEFT, SaicNavigationMapper.legacyType(NextEvent.OFFRAMP, Side.LEFT))
        assertEquals(NavigationType.FORK_RIGHT, SaicNavigationMapper.legacyType(NextEvent.FORK, Side.RIGHT))
        assertEquals(NavigationType.MERGE_LEFT, SaicNavigationMapper.legacyType(NextEvent.MERGE, Side.LEFT))
        assertEquals(NavigationType.MERGE_SIDE_UNSPECIFIED, SaicNavigationMapper.legacyType(NextEvent.MERGE, Side.UNSPECIFIED))
    }

    @Test
    fun legacyEventsThatNeedASideAreDroppedWithoutOne() {
        assertNull(SaicNavigationMapper.legacyType(NextEvent.TURN, Side.UNSPECIFIED))
        assertNull(SaicNavigationMapper.legacyType(NextEvent.FORK, Side.UNSPECIFIED))
        assertNull(SaicNavigationMapper.legacyType(NextEvent.UNKNOWN, Side.LEFT))
    }

    @Test
    fun legacyEventsWithoutASideDoNotNeedOne() {
        assertEquals(NavigationType.DEPART, SaicNavigationMapper.legacyType(NextEvent.DEPART, Side.UNSPECIFIED))
        assertEquals(NavigationType.STRAIGHT, SaicNavigationMapper.legacyType(NextEvent.STRAIGHT, Side.UNSPECIFIED))
        assertEquals(NavigationType.DESTINATION, SaicNavigationMapper.legacyType(NextEvent.DESTINATION, Side.UNSPECIFIED))
        assertEquals(NavigationType.ROUNDABOUT_ENTER, SaicNavigationMapper.legacyType(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.UNSPECIFIED))
    }

    @Test
    fun ferriesAndDestinationsHaveTheirOwnIcons() {
        assertEquals(ClusterIdTelenav.FERRY, SaicNavigationMapper.icon(NavigationType.FERRY_BOAT))
        assertEquals(ClusterIdTelenav.FERRY, SaicNavigationMapper.icon(NavigationType.FERRY_TRAIN))
        assertEquals(ClusterIdTelenav.LOCATION_AHEAD, SaicNavigationMapper.icon(NavigationType.DESTINATION))
        assertEquals(ClusterIdTelenav.LOCATION_AHEAD, SaicNavigationMapper.icon(NavigationType.DESTINATION_STRAIGHT))
        assertEquals(ClusterIdTelenav.LOCATION_LEFT, SaicNavigationMapper.icon(NavigationType.DESTINATION_LEFT))
        assertEquals(ClusterIdTelenav.LOCATION_RIGHT, SaicNavigationMapper.icon(NavigationType.DESTINATION_RIGHT))
    }

    @Test
    fun onlyTheManeuversWithNothingToDrawHaveNoIcon() {
        val withoutIcon = setOf(NavigationType.ROUNDABOUT_ENTER)
        for (type in NavigationType.values()) {
            if (type in withoutIcon) assertNull("$type", SaicNavigationMapper.icon(type))
            else assertNotNull("no icon for $type", SaicNavigationMapper.icon(type))
        }
    }

    @Test
    fun firstFrameAnnouncesGuidanceBeforeTheDetails() {
        val calls = SaicCallPlan.plan(null, frame())
        assertEquals(SaicCall.MapNavigating(true), calls[0])
        assertEquals(SaicCall.RequestProjection, calls[1])
        assertEquals(SaicCall.GuideStatus(1), calls[2])
        assertTrue(SaicCall.GuideInfos(ClusterIdTelenav.TURN_LEFT, 300, "Rue Victor Hugo") in calls)
        assertTrue(SaicCall.RoadInfo("Rue Victor Hugo") in calls)
        assertTrue(SaicCall.RemainingDistance(5000) in calls)
        assertTrue(SaicCall.RemainingTime(10) in calls)
    }

    @Test
    fun anUnchangedFrameSaysNothing() {
        assertEquals(emptyList<SaicCall>(), SaicCallPlan.plan(frame(), frame()))
    }

    @Test
    fun aShorterDistanceOnlyUpdatesTheGuideInfo() {
        val calls = SaicCallPlan.plan(frame(distance = 300), frame(distance = 250))
        assertEquals(listOf<SaicCall>(SaicCall.GuideInfos(ClusterIdTelenav.TURN_LEFT, 250, "Rue Victor Hugo")), calls)
    }

    @Test
    fun aNewManeuverUpdatesTheGuideInfoWithTheNewIcon() {
        val calls = SaicCallPlan.plan(frame(icon = ClusterIdTelenav.TURN_LEFT), frame(icon = ClusterIdTelenav.STAY_RIGHT))
        assertEquals(listOf<SaicCall>(SaicCall.GuideInfos(ClusterIdTelenav.STAY_RIGHT, 300, "Rue Victor Hugo")), calls)
    }

    @Test
    fun theRemainingTimeIsRoundedUpToWholeMinutes() {
        val calls = SaicCallPlan.plan(frame(remainingSeconds = 600), frame(remainingSeconds = 541))
        assertTrue(calls.none { it is SaicCall.RemainingTime })
        val next = SaicCallPlan.plan(frame(remainingSeconds = 541), frame(remainingSeconds = 539))
        assertEquals(listOf<SaicCall>(SaicCall.RemainingTime(9)), next)
    }

    @Test
    fun missingRemainingValuesAreNotSent() {
        val calls = SaicCallPlan.plan(null, frame(remainingMeters = -1, remainingSeconds = -1))
        assertTrue(calls.none { it is SaicCall.RemainingDistance || it is SaicCall.RemainingTime })
    }

    @Test
    fun endingGuidanceClosesItAndNothingIsSaidTwice() {
        assertEquals(listOf(SaicCall.GuideStatus(0), SaicCall.MapNavigating(false)), SaicCallPlan.plan(frame(), null))
        assertEquals(emptyList<SaicCall>(), SaicCallPlan.plan(null, null))
    }

    @Test
    fun aMetricCarGetsTheDistanceInMeters() {
        assertEquals(300, SaicDistance.forCluster(300, SaicDistance.UNIT_METRIC))
        assertEquals(0, SaicDistance.forCluster(0, SaicDistance.UNIT_METRIC))
    }

    @Test
    fun anImperialCarGetsTheDistanceInFeetRoundedToTheNearest() {
        assertEquals(328, SaicDistance.forCluster(100, SaicDistance.UNIT_IMPERIAL))
        assertEquals(98, SaicDistance.forCluster(30, SaicDistance.UNIT_IMPERIAL))
        assertEquals(0, SaicDistance.forCluster(0, SaicDistance.UNIT_IMPERIAL))
        assertEquals(16404, SaicDistance.forCluster(5000, SaicDistance.UNIT_IMPERIAL))
    }

    @Test
    fun anUnitTheAdapterDoesNotKnowIsTreatedAsMetric() {
        assertEquals(300, SaicDistance.forCluster(300, 0))
        assertEquals(300, SaicDistance.forCluster(300, 3))
    }

    /** What Google Maps sends: only the older events, as logged on the car. */
    private fun legacyFrame(event: NextEvent?, side: Side, number: Int, angle: Int, rightHandDrive: Boolean = false): SaicGuidance? {
        val detail = NextTurnDetail.newBuilder().setRoad("D400").setSide(side)
            .setTurnNumber(number).setTurnAngle(angle).apply { if (event != null) setNextTurn(event) }.build()
        val distance = NextTurnDistanceEvent.newBuilder().setDistanceMeters(200).build()
        @Suppress("DEPRECATION")
        val snapshot = NavigationSnapshot(nextTurnDetail = TimedMessage(detail, 1), nextTurnDistance = TimedMessage(distance, 1))
        return SaicNavigationMapper.from(snapshot, rightHandDrive)
    }

    @Test
    fun aRoundaboutOfTheOlderEventShowsItsExit() {
        // (exit number, angle) pairs seen on the car, in right-hand traffic.
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_1, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.RIGHT, 2, 90)?.icon)
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_3, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.RIGHT, 2, 180)?.icon)
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.RIGHT, 3, 270)?.icon)
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_2, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.RIGHT, 1, 135)?.icon)
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_4, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.RIGHT, 1, 225)?.icon)
    }

    @Test
    fun aLeftTrafficRoundaboutOfTheOlderEventGoesTheOtherWay() {
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.LEFT, 2, 90)?.icon)
        assertEquals(ClusterIdTelenav.ROUND_ABOUT_5, legacyFrame(NextEvent.ROUNDABOUT_ENTER_AND_EXIT, Side.UNSPECIFIED, 2, 90, rightHandDrive = true)?.icon)
    }

    @Test
    fun theExitFieldsOfOtherOlderEventsAreIgnored() {
        // The phone leaves the last roundabout's number and angle on a merge.
        assertEquals(ClusterIdTelenav.CONTINUE, legacyFrame(NextEvent.MERGE, Side.UNSPECIFIED, 4, 270)?.icon)
        assertEquals(ClusterIdTelenav.TURN_SLIGHT_RIGHT, legacyFrame(NextEvent.ROUNDABOUT_EXIT, Side.RIGHT, 0, 0)?.icon)
    }

    @Test
    fun theArrivalShowsTheDestinationOnItsSide() {
        // Maps sends the arrival with no maneuver, only the side.
        assertEquals(ClusterIdTelenav.LOCATION_RIGHT, legacyFrame(null, Side.RIGHT, 0, 0)?.icon)
        assertEquals(ClusterIdTelenav.LOCATION_LEFT, legacyFrame(null, Side.LEFT, 0, 0)?.icon)
        assertNull(legacyFrame(null, Side.UNSPECIFIED, 0, 0))
        assertEquals(ClusterIdTelenav.LOCATION_RIGHT, legacyFrame(NextEvent.DESTINATION, Side.RIGHT, 0, 0)?.icon)
        assertEquals(ClusterIdTelenav.LOCATION_AHEAD, legacyFrame(NextEvent.DESTINATION, Side.UNSPECIFIED, 0, 0)?.icon)
    }
}
