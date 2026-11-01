package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Handles navigation messages from the ID_NAV channel from any Android Auto-enabled app
 * (Google Maps, Yandex Maps, etc.). Shows notifications with turn-by-turn directions and current street.
 */
class AapNavigation(
    private val context: Context,
    private val settings: Settings
) {
    init {
        com.andrerinas.openheadunit.hud.SaicNavigationOutputs.start(context)
    }

    private val helper = AapNavigationHelper(context)
    private val snapshot = AapNavigationHelper.NavigationSnapshot()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var isBroadcastScheduled = false
    private var pendingNavEventType = NAV_EVENT_TYPE_TURN

    private val debouncedBroadcastEmitter = Runnable {
        isBroadcastScheduled = false
        helper.sendFullNavigationBroadcast(snapshot, pendingNavEventType)
    }

    private val lastLogged = HashMap<String, String>()

    /** Logs a navigation line at info level, only when it differs from the last one of its kind (the phone repeats them). */
    private fun logOnChange(kind: String, text: String) {
        if (lastLogged[kind] == text) return
        lastLogged[kind] = text
        AppLog.i("NavDiag $kind: $text")
    }

    fun process(message: AapMessage): Boolean {
        if (message.channel != Channel.ID_NAV) return false

        return when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                if (AppLog.LOG_DEBUG) AppLog.d("Nav: Instrument cluster start")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_START)
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                if (AppLog.LOG_DEBUG) AppLog.d("Nav: Instrument cluster stop")
                clearAccumulatedData()
                scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STOP)
                helper.cancelNotification()
                true
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> {
                try {
                    val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).build()
                    if (AppLog.LOG_DEBUG) AppLog.d("Nav: Navigation status=${status.status}")
                    updateClusterStatus(status)
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATUS)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationClusterStatus", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> {
                try {
                    val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                    logOnChange(
                        "legacy",
                        "nextTurn=${if (detail.hasNextTurn()) detail.nextTurn.name else "-"} " +
                            "side=${if (detail.hasSide()) detail.side.name else "-"} " +
                            "turnNumber=${if (detail.hasTurnNumber()) detail.turnNumber else "-"} " +
                            "turnAngle=${if (detail.hasTurnAngle()) detail.turnAngle else "-"} " +
                            "imageBytes=${if (detail.hasImage()) detail.image.size() else 0} road=${detail.road}"
                    )
                    snapshot.nextTurnDetail = AapNavigationHelper.TimedMessage(detail, helper.nowElapsedRealtimeMs())
                    val road = detail.road.takeIf { it.isNotBlank() }
                    road?.let {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(it, helper.nowElapsedRealtimeMs())
                    }
                    if (AppLog.LOG_DEBUG) {
                        AppLog.d(
                            "Nav: NextTurnDetail road=${detail.road} " +
                                    "hasNextTurn=${detail.hasNextTurn()} nextTurn=${detail.nextTurn}"
                        )
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = null)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDetail", e)
                    true
                }
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> {
                try {
                    val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                    snapshot.nextTurnDistance = AapNavigationHelper.TimedMessage(event, helper.nowElapsedRealtimeMs())
                    val distanceMeters = event.distanceMeters.takeIf { it >= 0 }
                    if (AppLog.LOG_DEBUG) {
                        AppLog.d(
                            "Nav: NextTurnDistanceEvent hasDistance=${event.hasDistanceMeters()} " +
                                    "distance=${event.distanceMeters} hasTime=${event.hasTimeToTurnSeconds()} " +
                                    "time=${event.timeToTurnSeconds}"
                        )
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_TURN)
                    if (settings.showNavigationNotifications) {
                        helper.showNotificationForSnapshot(snapshot, distanceMeters = distanceMeters)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NextTurnDistanceEvent", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> {
                try {
                    val state = message.parse(NavigationStatus.NavigationState.newBuilder()).build()
                    val maneuver = state.stepsList.firstOrNull()?.takeIf { it.hasManeuver() }?.maneuver
                    logOnChange(
                        "state",
                        "steps=${state.stepsCount} type=${if (maneuver?.hasType() == true) maneuver.type.name else "-"} " +
                            "exitNumber=${if (maneuver?.hasRoundaboutExitNumber() == true) maneuver.roundaboutExitNumber else "-"} " +
                            "exitAngle=${if (maneuver?.hasRoundaboutExitAngle() == true) maneuver.roundaboutExitAngle else "-"}"
                    )
                    snapshot.navigationState = AapNavigationHelper.TimedMessage(state, helper.nowElapsedRealtimeMs())
                    val firstStepRoad = state.stepsList.firstOrNull()
                        ?.takeIf { it.hasRoad() && it.road.hasName() }
                        ?.road
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                    if (!firstStepRoad.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(firstStepRoad, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_STATE)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationState", e)
                    true
                }
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> {
                try {
                    val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).build()
                    snapshot.currentPosition = AapNavigationHelper.TimedMessage(position, helper.nowElapsedRealtimeMs())
                    val road = position
                        .takeIf { it.hasCurrentRoad() && it.currentRoad.hasName() }
                        ?.currentRoad
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: snapshot.currentStreet?.payload
                    if (!road.isNullOrBlank()) {
                        snapshot.currentStreet = AapNavigationHelper.TimedMessage(road, helper.nowElapsedRealtimeMs())
                    }
                    scheduleDebouncedBroadcast(NAV_EVENT_TYPE_CURRENT_POSITION)
                    true
                } catch (e: Exception) {
                    AppLog.e("Nav: failed to parse NavigationCurrentPosition", e)
                    true
                }
            }
            else -> {
                if (AppLog.LOG_DEBUG) AppLog.d("Nav: passthrough type ${message.type}")
                false
            }
        }
    }

    private fun clearAccumulatedData() {
        snapshot.clusterStatus = null
        snapshot.nextTurnDetail = null
        snapshot.nextTurnDistance = null
        snapshot.navigationState = null
        snapshot.currentPosition = null
        snapshot.currentStreet = null
    }

    private fun clearAccumulatedDataPreservingStatus(
        status: AapNavigationHelper.TimedMessage<NavigationStatus.NavigationClusterStatus>
    ) {
        clearAccumulatedData()
        snapshot.clusterStatus = status
    }

    private fun updateClusterStatus(status: NavigationStatus.NavigationClusterStatus) {
        val now = helper.nowElapsedRealtimeMs()
        val newStatus = AapNavigationHelper.TimedMessage(status, now)
        val previous = snapshot.clusterStatus?.payload?.status
        val changed = previous != null && previous != status.status
        if (changed) {
            clearAccumulatedDataPreservingStatus(newStatus)
            helper.cancelNotification()
        } else {
            snapshot.clusterStatus = newStatus
        }
    }

    private fun scheduleDebouncedBroadcast(navEventType: Int) {
        val guidance = com.andrerinas.openheadunit.hud.SaicNavigationMapper.from(snapshot, settings.rightHandDrive)
        logOnChange("cluster", guidance?.let { "icon=${it.icon.name} (${it.icon.id})" } ?: "none")
        com.andrerinas.openheadunit.hud.SaicNavigationOutputs.update(guidance)
        pendingNavEventType = navEventType
        if (isBroadcastScheduled) return
        isBroadcastScheduled = true
        debounceHandler.postDelayed(debouncedBroadcastEmitter, BROADCAST_DEBOUNCE_MS)
    }

    companion object {
        private const val BROADCAST_DEBOUNCE_MS = 1000L
        private const val NAV_EVENT_TYPE_TURN = 0
        private const val NAV_EVENT_TYPE_START = 1
        private const val NAV_EVENT_TYPE_STOP = 2
        private const val NAV_EVENT_TYPE_STATUS = 3
        private const val NAV_EVENT_TYPE_STATE = 4
        private const val NAV_EVENT_TYPE_CURRENT_POSITION = 5

        fun createNotificationChannel(context: Context) {
            AapNavigationHelper.createNotificationChannel(context)
        }
    }
}