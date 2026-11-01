package com.andrerinas.openheadunit.hud

import com.andrerinas.openheadunit.aap.AapNavigationHelper.NavigationSnapshot
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side

/**
 * The icon ids the instrument cluster takes as `tbtType` in guideInfosChange: the EU/UK TeleNav set
 * (`ClusterIdTelenav`, drawables `ic_tbt_N`). Names and ids follow the MG4-AA-IPK-NavigationMapper
 * project, which lists them as verified on a vehicle. Each icon also exists in a left-hand and a
 * right-hand traffic variant, and the cluster picks the one that fits the car.
 */
internal enum class ClusterIdTelenav(val id: Int) {
    CONTINUE(0),
    TURN_LEFT(1),
    TURN_RIGHT(2),
    TURN_SLIGHT_LEFT(3),
    TURN_SLIGHT_RIGHT(4),
    LEFT_U_TURN(5),
    RIGHT_U_TURN(6),
    TURN_HARD_RIGHT(7),
    TURN_HARD_LEFT(8),

    /** Roundabout, exit at 12 o'clock (straight through). */
    ROUND_ABOUT_3(9),

    /** Roundabout, exit at 9 o'clock. */
    ROUND_ABOUT_5(10),

    /** Roundabout, exit at 3 o'clock. */
    ROUND_ABOUT_1(11),

    /** Roundabout, exit at 6 o'clock (u-turn in the roundabout). */
    ROUND_ABOUT_7(12),

    /** Roundabout, exit at 1 o'clock. */
    ROUND_ABOUT_2(13),

    /** Roundabout, exit at 5 o'clock. */
    ROUND_ABOUT_0(14),

    /** Roundabout, exit at 7 o'clock. */
    ROUND_ABOUT_6(15),

    /** Roundabout, exit at 11 o'clock. */
    ROUND_ABOUT_4(16),
    STAY_LEFT(17),
    STAY_RIGHT(18),
    FERRY(19),
    LOCATION_AHEAD(20),
    LOCATION_LEFT(21),
    LOCATION_RIGHT(22),
    LOCATION_AHEAD_1(23),
    LOCATION_LEFT_1(24),
    LOCATION_RIGHT_1(25),
    LOCATION_AHEAD_2(26),
    LOCATION_LEFT_2(27),
    LOCATION_RIGHT_2(28),
    LOCATION_AHEAD_3(29),
    LOCATION_LEFT_3(30),
    LOCATION_RIGHT_3(31),
    ENTER_RIGHT(32),
    MERGE_LEFT(33),
    ENTER_LEFT(34),
    MERGE_RIGHT(35),
    EXIT_LEFT(36),
    EXIT_RIGHT(37),
    PASS_TOLL_BOOTH(38),
    HOOK_TURN_RIGHT(39),
}

/** One guidance frame in the vocabulary of the SAIC map adapter (see [SaicNavigationOutputs]). */
internal data class SaicGuidance(
    val icon: ClusterIdTelenav,
    val distanceMeters: Int,
    val road: String,
    val remainingMeters: Int = -1,
    val remainingSeconds: Int = -1,
    val updatedNs: Long = System.nanoTime(),
)

/** Android Auto guidance snapshot to [SaicGuidance]. Reads the snapshot the same way [BydNavigationMapper] does. */
internal object SaicNavigationMapper {

    fun from(snapshot: NavigationSnapshot, rightHandDrive: Boolean = false): SaicGuidance? {
        val status = snapshot.clusterStatus?.payload?.status
        if (status != null && status != NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE) return null
        val step = snapshot.navigationState?.payload?.stepsList?.firstOrNull()
        if (snapshot.navigationState != null && step == null) return null
        val legacy = snapshot.nextTurnDetail?.payload
        val modern = step?.takeIf { it.hasManeuver() && it.maneuver.hasType() }?.maneuver
        // Google Maps sends only the older event, and for a roundabout it carries the exit number and angle.
        val legacyRoundabout = modern == null && legacy?.takeIf { it.hasNextTurn() }?.nextTurn == NextEvent.ROUNDABOUT_ENTER_AND_EXIT
        val type: NavigationType = modern?.type ?: legacy?.let {
            val side = if (it.hasSide()) it.side else Side.UNSPECIFIED
            when {
                legacyRoundabout -> legacyRoundaboutType(side, rightHandDrive)
                it.hasNextTurn() -> legacyType(it.nextTurn, side)
                // The arrival: Maps sends no maneuver, only the side the destination lies on.
                else -> destinationType(side)
            }
        } ?: return null
        // The exit fields of the older event keep their last value on other events, so they count for a roundabout only.
        val exitNumber = modern?.takeIf { it.hasRoundaboutExitNumber() }?.roundaboutExitNumber
            ?: legacy?.takeIf { legacyRoundabout && it.hasTurnNumber() }?.turnNumber
        val exitAngle = modern?.takeIf { it.hasRoundaboutExitAngle() }?.roundaboutExitAngle
            ?: legacy?.takeIf { legacyRoundabout && it.hasTurnAngle() }?.turnAngle
        val icon = icon(type, exitNumber, exitAngle, rightHandDrive) ?: return null
        val position = snapshot.currentPosition?.payload
        val distance = distanceMeters(snapshot) ?: return null // An unknown distance must not be shown as "now".
        if (distance < 0) return null
        val road = step?.takeIf { it.hasRoad() && it.road.hasName() }?.road?.name?.takeIf { it.isNotBlank() }
            ?: legacy?.road?.takeIf { it.isNotBlank() } ?: snapshot.currentStreet?.payload.orEmpty()
        val destination = position?.destinationDistancesList?.firstOrNull()
        val remaining = destination?.takeIf { it.hasDistance() && it.distance.hasMeters() }?.distance?.meters ?: -1
        val seconds = destination?.takeIf { it.hasTimeToArrivalSeconds() }?.timeToArrivalSeconds
            ?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: -1
        return SaicGuidance(icon, distance, road, remaining, seconds)
    }

    /**
     * Meters to the next maneuver. Newer phones put it in the current position, older ones in the
     * next-turn distance event; a phone that sends both may leave the older one behind, so the more
     * recent of the two is the current one.
     */
    internal fun distanceMeters(snapshot: NavigationSnapshot): Int? {
        val position = snapshot.currentPosition?.takeIf {
            val payload = it.payload
            payload.hasStepDistance() && payload.stepDistance.hasDistance() && payload.stepDistance.distance.hasMeters()
        }
        @Suppress("DEPRECATION")
        val legacy = snapshot.nextTurnDistance?.takeIf { it.payload.hasDistanceMeters() }
        return when {
            position != null && (legacy == null || position.updatedAtElapsedRealtimeMs >= legacy.updatedAtElapsedRealtimeMs) ->
                position.payload.stepDistance.distance.meters
            else -> legacy?.payload?.distanceMeters
        }
    }

    /** The destination icon for the side it lies on; null when the side is not known. */
    private fun destinationType(side: Side): NavigationType? = when (side) {
        Side.LEFT -> NavigationType.DESTINATION_LEFT
        Side.RIGHT -> NavigationType.DESTINATION_RIGHT
        else -> null
    }

    /**
     * The roundabout of the older turn event: its side is the side of the traffic, so a roundabout is
     * driven counter-clockwise with the traffic on the right and clockwise with it on the left. With no
     * side, the steering side of the car stands in for it (steering on the right, traffic on the left).
     */
    fun legacyRoundaboutType(side: Side, rightHandDrive: Boolean): NavigationType {
        val clockwise = when (side) {
            Side.LEFT -> true
            Side.RIGHT -> false
            else -> rightHandDrive
        }
        return if (clockwise) NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE
        else NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
    }

    /**
     * The older turn event (NextTurnDetail) carries an event and a side instead of a maneuver type.
     * Null when the side is needed and missing, or for an event with no equivalent.
     */
    fun legacyType(event: NextEvent, side: Side): NavigationType? {
        fun bySide(left: NavigationType, right: NavigationType): NavigationType? = when (side) {
            Side.LEFT -> left
            Side.RIGHT -> right
            else -> null
        }
        return when (event) {
            NextEvent.DEPART -> NavigationType.DEPART
            NextEvent.NAME_CHANGE -> NavigationType.NAME_CHANGE
            NextEvent.SLIGHT_TURN -> bySide(NavigationType.TURN_SLIGHT_LEFT, NavigationType.TURN_SLIGHT_RIGHT)
            NextEvent.TURN -> bySide(NavigationType.TURN_NORMAL_LEFT, NavigationType.TURN_NORMAL_RIGHT)
            NextEvent.SHARP_TURN -> bySide(NavigationType.TURN_SHARP_LEFT, NavigationType.TURN_SHARP_RIGHT)
            NextEvent.U_TURN -> bySide(NavigationType.U_TURN_LEFT, NavigationType.U_TURN_RIGHT)
            NextEvent.ON_RAMP -> bySide(NavigationType.ON_RAMP_NORMAL_LEFT, NavigationType.ON_RAMP_NORMAL_RIGHT)
            NextEvent.OFFRAMP -> bySide(NavigationType.OFF_RAMP_NORMAL_LEFT, NavigationType.OFF_RAMP_NORMAL_RIGHT)
            NextEvent.FORK -> bySide(NavigationType.FORK_LEFT, NavigationType.FORK_RIGHT)
            NextEvent.MERGE ->
                if (side == Side.UNSPECIFIED) NavigationType.MERGE_SIDE_UNSPECIFIED
                else bySide(NavigationType.MERGE_LEFT, NavigationType.MERGE_RIGHT)
            // The legacy event does not say which way the roundabout turns, so no exit can be drawn.
            NextEvent.ROUNDABOUT_ENTER,
            NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> NavigationType.ROUNDABOUT_ENTER
            NextEvent.ROUNDABOUT_EXIT -> NavigationType.ROUNDABOUT_EXIT
            NextEvent.STRAIGHT -> NavigationType.STRAIGHT
            NextEvent.FERRY_BOAT -> NavigationType.FERRY_BOAT
            NextEvent.FERRY_TRAIN -> NavigationType.FERRY_TRAIN
            NextEvent.DESTINATION -> destinationType(side) ?: NavigationType.DESTINATION
            else -> null
        }
    }

    /**
     * Android Auto maneuver to the cluster icon, following the table of the Navigation SDK maneuvers
     * matched to the TeleNav icons (a ramp or a keep keeps the icon of its shape: ON_RAMP_SLIGHT_LEFT
     * is a slight turn, OFF_RAMP_NORMAL_LEFT an exit).
     *
     * Roundabouts use the eight exit-position icons, chosen from the exit angle (or exit number) and
     * the direction of circulation: see [roundaboutIcon]. Null for a maneuver the table leaves empty
     * (a merge or a roundabout with no side or exit), so the cluster shows nothing rather than a
     * misleading arrow.
     *
     * Note the table pairs the "bear" icons (ids 3 and 4) with forks and the roundabout exit, and the
     * "slide" icons (ids 17 and 18) with keeps and slight turns, the other way round from the names of
     * [ClusterIdTelenav]. [rightHandDrive] is the steering side of the car: the roundabout exit takes
     * the side of the traffic, the left in a right-hand-drive car.
     */
    fun icon(
        type: NavigationType,
        exitNumber: Int? = null,
        exitAngle: Int? = null,
        rightHandDrive: Boolean = false,
    ): ClusterIdTelenav? = when (type) {
        NavigationType.UNKNOWN,
        NavigationType.DEPART,
        NavigationType.NAME_CHANGE,
        NavigationType.MERGE_SIDE_UNSPECIFIED,
        NavigationType.STRAIGHT -> ClusterIdTelenav.CONTINUE

        NavigationType.TURN_NORMAL_LEFT,
        NavigationType.ON_RAMP_NORMAL_LEFT -> ClusterIdTelenav.TURN_LEFT
        NavigationType.TURN_NORMAL_RIGHT,
        NavigationType.ON_RAMP_NORMAL_RIGHT -> ClusterIdTelenav.TURN_RIGHT

        NavigationType.KEEP_LEFT,
        NavigationType.TURN_SLIGHT_LEFT,
        NavigationType.ON_RAMP_SLIGHT_LEFT,
        NavigationType.OFF_RAMP_SLIGHT_LEFT -> ClusterIdTelenav.STAY_LEFT
        NavigationType.KEEP_RIGHT,
        NavigationType.TURN_SLIGHT_RIGHT,
        NavigationType.ON_RAMP_SLIGHT_RIGHT,
        NavigationType.OFF_RAMP_SLIGHT_RIGHT -> ClusterIdTelenav.STAY_RIGHT

        NavigationType.TURN_SHARP_LEFT,
        NavigationType.ON_RAMP_SHARP_LEFT -> ClusterIdTelenav.TURN_HARD_LEFT
        NavigationType.TURN_SHARP_RIGHT,
        NavigationType.ON_RAMP_SHARP_RIGHT -> ClusterIdTelenav.TURN_HARD_RIGHT

        NavigationType.U_TURN_LEFT,
        NavigationType.ON_RAMP_U_TURN_LEFT -> ClusterIdTelenav.LEFT_U_TURN
        NavigationType.U_TURN_RIGHT,
        NavigationType.ON_RAMP_U_TURN_RIGHT -> ClusterIdTelenav.RIGHT_U_TURN

        NavigationType.FORK_LEFT -> ClusterIdTelenav.TURN_SLIGHT_LEFT
        NavigationType.FORK_RIGHT -> ClusterIdTelenav.TURN_SLIGHT_RIGHT

        NavigationType.OFF_RAMP_NORMAL_LEFT,
        NavigationType.MERGE_LEFT -> ClusterIdTelenav.EXIT_LEFT
        NavigationType.OFF_RAMP_NORMAL_RIGHT,
        NavigationType.MERGE_RIGHT -> ClusterIdTelenav.EXIT_RIGHT

        // No exit named: the table has no icon.
        NavigationType.ROUNDABOUT_ENTER -> null
        // Leaving a roundabout: a slight turn to the side the exit lies on (right in right-hand traffic).
        NavigationType.ROUNDABOUT_EXIT ->
            if (rightHandDrive) ClusterIdTelenav.TURN_SLIGHT_LEFT else ClusterIdTelenav.TURN_SLIGHT_RIGHT
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE ->
            roundaboutIcon(Circulation.CLOCKWISE, exitNumber, exitAngle)
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
            roundaboutIcon(Circulation.COUNTER_CLOCKWISE, exitNumber, exitAngle)

        NavigationType.FERRY_BOAT,
        NavigationType.FERRY_TRAIN -> ClusterIdTelenav.FERRY

        NavigationType.DESTINATION,
        NavigationType.DESTINATION_STRAIGHT -> ClusterIdTelenav.LOCATION_AHEAD
        NavigationType.DESTINATION_LEFT -> ClusterIdTelenav.LOCATION_LEFT
        NavigationType.DESTINATION_RIGHT -> ClusterIdTelenav.LOCATION_RIGHT
    }

    /** Direction of travel around the roundabout: clockwise in left-hand traffic, counter-clockwise in right-hand. */
    enum class Circulation { CLOCKWISE, COUNTER_CLOCKWISE }

    /**
     * Where the exit lies, as a clock face seen from the entry heading (12 = straight on, 3 = right,
     * 6 = back the way you came), and the cluster icon that shows it. The cluster has no icon for
     * 2, 4, 8 and 10 o'clock; those take the nearest one, ties going to the icon listed first.
     */
    private val roundaboutIcons = listOf(
        0 to ClusterIdTelenav.ROUND_ABOUT_3,   // 12 o'clock
        30 to ClusterIdTelenav.ROUND_ABOUT_2,  // 1 o'clock
        90 to ClusterIdTelenav.ROUND_ABOUT_1,  // 3 o'clock
        150 to ClusterIdTelenav.ROUND_ABOUT_0, // 5 o'clock
        180 to ClusterIdTelenav.ROUND_ABOUT_7, // 6 o'clock
        210 to ClusterIdTelenav.ROUND_ABOUT_6, // 7 o'clock
        270 to ClusterIdTelenav.ROUND_ABOUT_5, // 9 o'clock
        330 to ClusterIdTelenav.ROUND_ABOUT_4, // 11 o'clock
    )

    /**
     * The icon for a roundabout exit.
     *
     * Android Auto gives the exit angle as the degrees travelled around the roundabout between the
     * entrance and the exit, from 1 to 360: with four evenly spaced exits, 90, 180, 270 and 360.
     * Going round counter-clockwise, 90 is the exit on the right and 180 the one straight ahead; going
     * round clockwise, 90 is on the left. So the exit lies at a heading, measured clockwise from the
     * entry heading, of (180 - angle) going counter-clockwise and (180 + angle) going clockwise.
     *
     * With only an exit number, the angle is estimated as for evenly spaced exits (90 degrees per
     * exit) and, from the fourth exit on, as 270, a left turn: an estimate, since the number of exits
     * is not known. With neither, the straight-through icon.
     */
    fun roundaboutIcon(circulation: Circulation, exitNumber: Int?, exitAngle: Int?): ClusterIdTelenav {
        val heading = roundaboutHeading(circulation, exitNumber, exitAngle) ?: return ClusterIdTelenav.ROUND_ABOUT_3
        return roundaboutIcons.minByOrNull { (position, _) ->
            val gap = Math.abs(heading - position)
            minOf(gap, 360 - gap)
        }!!.second
    }

    /** Heading of the exit in degrees clockwise from the entry heading (0 = straight on), null with no angle or number. */
    fun roundaboutHeading(circulation: Circulation, exitNumber: Int?, exitAngle: Int?): Int? {
        val angle = exitAngle?.takeIf { it in 1..360 }
            ?: exitNumber?.takeIf { it >= 1 }?.let { minOf(it, 3) * 90 }
            ?: return null
        return Math.floorMod(if (circulation == Circulation.COUNTER_CLOCKWISE) 180 - angle else 180 + angle, 360)
    }
}
