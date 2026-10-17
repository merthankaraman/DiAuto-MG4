package com.andrerinas.openheadunit.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import com.andrerinas.openheadunit.aap.AapNavigationHelper
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NextTurnDetail.Side
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.roundToInt

/**
 * Feeds Android Auto turn-by-turn into the MG4 instrument cluster's navigation card through SAIC's
 * nav adapter (com.saicmotor.adapterservice/.services.MapService) — the exported, unguarded binder
 * interface the built-in Telenav navigation uses. The cluster shows the card while guide status is 1.
 */
object SaicClusterNavigation {
    private const val ADAPTER_PKG = "com.saicmotor.adapterservice"
    private const val MAP_DESCRIPTOR = "com.saicmotor.adapterservice.IMapService"
    private const val GENERAL_DESCRIPTOR = "com.saicmotor.adapterservice.IGeneralService"
    private const val TX_GUIDE_STATUS = 29
    private const val TX_GUIDE_INFOS = 30
    private const val TX_GET_DISTANCE_UNIT = 28
    private const val DISTANCE_UNIT_IMPERIAL = 2
    private const val GUIDE_START = 1
    private const val GUIDE_STOP = 0
    private const val FEET_PER_METRE = 3.28084

    private var context: Context? = null
    private var handler: Handler? = null
    @Volatile private var mapBinder: IBinder? = null
    @Volatile private var generalBinder: IBinder? = null
    private var guiding = false
    private var lastSent: Triple<Int, Int, String>? = null

    private val mapConnection = binderConnection { mapBinder = it }
    private val generalConnection = binderConnection { generalBinder = it }

    @Synchronized fun start(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        val installed = try { app.packageManager.getPackageInfo(ADAPTER_PKG, 0); true } catch (e: Exception) { false }
        if (!installed) return
        this.context = app
        handler = Handler(HandlerThread("SaicClusterNav").apply { start() }.looper)
        bind(app, "$ADAPTER_PKG.services.MapService", mapConnection)
        bind(app, "$ADAPTER_PKG.services.GeneralService", generalConnection)
        AppLog.i("SaicClusterNav: binding SAIC nav adapter for the instrument cluster")
    }

    @Synchronized fun stop() {
        val app = context ?: return
        post { sendGuideStatus(GUIDE_STOP) }
        handler?.post {
            try { app.unbindService(mapConnection) } catch (_: Exception) {}
            try { app.unbindService(generalConnection) } catch (_: Exception) {}
            mapBinder = null
            generalBinder = null
            handler?.looper?.quitSafely()
            handler = null
        }
        context = null
    }

    /** Publishes the current Android Auto guidance; called after each debounced navigation update. */
    fun publish(snapshot: AapNavigationHelper.NavigationSnapshot, navigationEnded: Boolean) {
        if (context == null) return
        if (navigationEnded) { post { sendGuideStatus(GUIDE_STOP) }; return }
        val detail = snapshot.nextTurnDetail?.payload
        val maneuver = snapshot.navigationState?.payload?.stepsList?.firstOrNull()?.maneuver
        val distanceMetres = distanceMetres(snapshot)
        if (detail == null && maneuver == null) return
        val icon = if (maneuver != null && maneuver.type != NavigationType.UNKNOWN) iconFor(maneuver) else iconFor(detail)
        val road = snapshot.navigationState?.payload?.stepsList?.firstOrNull()
            ?.takeIf { it.hasRoad() && it.road.hasName() }?.road?.name
            ?: detail?.road.orEmpty()
        post { sendGuideInfos(icon, distanceMetres ?: -1, road, maneuver, detail) }
    }

    /**
     * Metres to the next maneuver. Newer phones report it in the current position rather than the
     * older next-turn distance event; when both arrive, the more recent one is current.
     */
    internal fun distanceMetres(snapshot: AapNavigationHelper.NavigationSnapshot): Int? {
        val position = snapshot.currentPosition?.takeIf { it.payload.stepDistance.distance.hasMeters() }
        val legacy = snapshot.nextTurnDistance?.takeIf { it.payload.hasDistanceMeters() }
        return when {
            position != null && (legacy == null || position.updatedAtElapsedRealtimeMs >= legacy.updatedAtElapsedRealtimeMs) ->
                position.payload.stepDistance.distance.meters
            else -> legacy?.payload?.distanceMeters
        }
    }

    /** Cluster icon (ic_tbt_N, the UK/EU set) for the richer navigation-state maneuver. */
    internal fun iconFor(maneuver: NavigationStatus.NavigationManeuver): Int = when (maneuver.type) {
        NavigationType.KEEP_LEFT -> 36
        NavigationType.KEEP_RIGHT -> 37
        NavigationType.TURN_SLIGHT_LEFT -> 3
        NavigationType.TURN_SLIGHT_RIGHT -> 4
        NavigationType.TURN_NORMAL_LEFT -> 1
        NavigationType.TURN_NORMAL_RIGHT -> 2
        NavigationType.TURN_SHARP_LEFT -> 8
        NavigationType.TURN_SHARP_RIGHT -> 7
        NavigationType.U_TURN_LEFT -> 5
        NavigationType.U_TURN_RIGHT -> 6
        NavigationType.ON_RAMP_SLIGHT_LEFT, NavigationType.ON_RAMP_NORMAL_LEFT, NavigationType.ON_RAMP_SHARP_LEFT,
        NavigationType.ON_RAMP_U_TURN_LEFT, NavigationType.MERGE_LEFT, NavigationType.MERGE_SIDE_UNSPECIFIED -> 33
        NavigationType.ON_RAMP_SLIGHT_RIGHT, NavigationType.ON_RAMP_NORMAL_RIGHT, NavigationType.ON_RAMP_SHARP_RIGHT,
        NavigationType.ON_RAMP_U_TURN_RIGHT, NavigationType.MERGE_RIGHT -> 32
        NavigationType.OFF_RAMP_SLIGHT_LEFT, NavigationType.OFF_RAMP_NORMAL_LEFT -> 34
        NavigationType.OFF_RAMP_SLIGHT_RIGHT, NavigationType.OFF_RAMP_NORMAL_RIGHT -> 35
        NavigationType.FORK_LEFT -> 17
        NavigationType.FORK_RIGHT -> 18
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW, NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE ->
            roundaboutIcon(maneuver.takeIf { it.hasRoundaboutExitAngle() }?.roundaboutExitAngle, clockwise = true)
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
            roundaboutIcon(maneuver.takeIf { it.hasRoundaboutExitAngle() }?.roundaboutExitAngle, clockwise = false)
        NavigationType.ROUNDABOUT_ENTER, NavigationType.ROUNDABOUT_EXIT -> 9
        NavigationType.FERRY_BOAT, NavigationType.FERRY_TRAIN -> 19
        NavigationType.DESTINATION, NavigationType.DESTINATION_STRAIGHT -> 20
        NavigationType.DESTINATION_LEFT -> 21
        NavigationType.DESTINATION_RIGHT -> 22
        else -> 0
    }

    /** Fallback for phones that only send the older next-turn detail. */
    internal fun iconFor(detail: NavigationStatus.NextTurnDetail?): Int {
        if (detail == null) return 0
        val left = detail.side == Side.LEFT
        val right = detail.side == Side.RIGHT
        fun sided(l: Int, r: Int, none: Int = 0) = if (left) l else if (right) r else none
        return when (detail.nextTurn) {
            NextEvent.SLIGHT_TURN -> sided(3, 4)
            NextEvent.TURN -> sided(1, 2)
            NextEvent.SHARP_TURN -> sided(8, 7)
            NextEvent.U_TURN -> sided(5, 6, 5)
            NextEvent.ON_RAMP, NextEvent.MERGE -> sided(33, 32, 33)
            NextEvent.OFFRAMP -> sided(34, 35, 35)
            NextEvent.FORK -> sided(17, 18, 17)
            NextEvent.ROUNDABOUT_ENTER, NextEvent.ROUNDABOUT_EXIT, NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> 9
            NextEvent.FERRY_BOAT, NextEvent.FERRY_TRAIN -> 19
            NextEvent.DESTINATION -> sided(21, 22, 20)
            else -> 0
        }
    }

    /**
     * Exit angle is measured from the entrance in the direction of traffic (androidx Maneuver), so on a
     * clockwise (UK) roundabout 90 is the left exit, 180 straight on, 270 right and 360 back round.
     */
    internal fun roundaboutIcon(angle: Int?, clockwise: Boolean): Int {
        if (angle == null || angle <= 0) return 9
        val (sharpNear, near, slightNear, slightFar, far, sharpFar) =
            if (clockwise) listOf(15, 10, 16, 13, 11, 14) else listOf(14, 11, 13, 16, 10, 15)
        return when (angle % 361) {
            in 1 until 68 -> sharpNear
            in 68 until 112 -> near
            in 112 until 158 -> slightNear
            in 158 until 202 -> 9
            in 202 until 248 -> slightFar
            in 248 until 292 -> far
            in 292 until 338 -> sharpFar
            else -> 12
        }
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun sendGuideInfos(
        icon: Int, distanceMetres: Int, road: String,
        maneuver: NavigationStatus.NavigationManeuver?, detail: NavigationStatus.NextTurnDetail?,
    ) {
        if (!guiding) sendGuideStatus(GUIDE_START)
        val distance = if (distanceMetres < 0) -1
            else if (distanceUnit() == DISTANCE_UNIT_IMPERIAL) (distanceMetres * FEET_PER_METRE).roundToInt()
            else distanceMetres
        val payload = Triple(icon, distance, road)
        if (payload == lastSent) return
        if (transact(mapBinder, MAP_DESCRIPTOR, TX_GUIDE_INFOS) { writeInt(icon); writeInt(distance); writeString(road) }) {
            lastSent = payload
            AppLog.i(
                "SaicClusterNav: icon=$icon distance=$distance road=\"$road\" " +
                    "(maneuver=${maneuver?.type} exitAngle=${maneuver?.roundaboutExitAngle} " +
                    "nextTurn=${detail?.nextTurn} side=${detail?.side} turnAngle=${detail?.turnAngle})"
            )
        }
    }

    private fun sendGuideStatus(status: Int) {
        if ((status == GUIDE_START) == guiding) return
        if (transact(mapBinder, MAP_DESCRIPTOR, TX_GUIDE_STATUS) { writeInt(status) }) {
            guiding = status == GUIDE_START
            if (!guiding) lastSent = null
            AppLog.i("SaicClusterNav: guide status=$status")
        }
    }

    private fun distanceUnit(): Int {
        val binder = generalBinder ?: return 1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(GENERAL_DESCRIPTOR)
            binder.transact(TX_GET_DISTANCE_UNIT, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun transact(binder: IBinder?, descriptor: String, code: Int, write: Parcel.() -> Unit): Boolean {
        if (binder == null) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            AppLog.w("SaicClusterNav: adapter call $code failed: ${e.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun post(block: () -> Unit) { handler?.post(block) }

    private fun bind(context: Context, service: String, connection: ServiceConnection) {
        try {
            context.bindService(Intent().setComponent(ComponentName(ADAPTER_PKG, service)), connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLog.w("SaicClusterNav: could not bind $service: ${e.message}")
        }
    }

    private fun binderConnection(onBinder: (IBinder?) -> Unit) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = onBinder(service)
        override fun onServiceDisconnected(name: ComponentName) = onBinder(null)
    }
}
