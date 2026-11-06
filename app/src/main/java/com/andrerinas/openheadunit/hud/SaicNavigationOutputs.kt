package com.andrerinas.openheadunit.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One call to the adapter's IMapService, in the order and shape its stub reads them. */
internal sealed class SaicCall {
    data class MapNavigating(val on: Boolean) : SaicCall()
    object RequestProjection : SaicCall() {
        override fun toString() = "RequestProjection"
    }
    data class GuideStatus(val status: Int) : SaicCall()
    data class GuideInfos(val icon: ClusterIdTelenav, val distanceMeters: Int, val text: String) : SaicCall()
    data class RoadInfo(val road: String) : SaicCall()
    data class RemainingDistance(val meters: Int) : SaicCall()
    data class RemainingTime(val minutes: Int) : SaicCall()
}

/**
 * The cluster shows the distance of guideInfosChange as it receives it and does nothing with the unit
 * the car is set to, so a car set to imperial units has to be sent feet.
 */
internal object SaicDistance {
    const val UNIT_METRIC = 1
    const val UNIT_IMPERIAL = 2
    private const val FEET_PER_METER = 3.28084

    fun forCluster(meters: Int, unit: Int): Int =
        if (unit == UNIT_IMPERIAL) Math.round(meters * FEET_PER_METER).toInt() else meters
}

/** What has to be said to the adapter to go from one state to the next. Pure, so it can be tested. */
internal object SaicCallPlan {
    const val STATUS_IDLE = 0
    const val STATUS_GUIDING = 1

    fun plan(previous: SaicGuidance?, next: SaicGuidance?): List<SaicCall> {
        if (next == null) {
            return if (previous == null) emptyList()
            else listOf(SaicCall.GuideStatus(STATUS_IDLE), SaicCall.MapNavigating(false))
        }
        val calls = ArrayList<SaicCall>(6)
        if (previous == null) {
            calls += SaicCall.MapNavigating(true)
            calls += SaicCall.RequestProjection
            calls += SaicCall.GuideStatus(STATUS_GUIDING)
        }
        if (previous == null || previous.icon != next.icon || previous.distanceMeters != next.distanceMeters || previous.road != next.road) {
            calls += SaicCall.GuideInfos(next.icon, next.distanceMeters, next.road)
        }
        if (next.road.isNotEmpty() && previous?.road != next.road) calls += SaicCall.RoadInfo(next.road)
        if (next.remainingMeters >= 0 && previous?.remainingMeters != next.remainingMeters) {
            calls += SaicCall.RemainingDistance(next.remainingMeters)
        }
        if (next.remainingSeconds >= 0) {
            val minutes = (next.remainingSeconds + 59) / 60
            val before = previous?.remainingSeconds?.takeIf { it >= 0 }?.let { (it + 59) / 60 }
            if (before != minutes) calls += SaicCall.RemainingTime(minutes)
        }
        return calls
    }
}

/**
 * Turn-by-turn guidance from Android Auto to the instrument cluster, through the head unit's own map
 * adapter (com.saicmotor.adapterservice, IMapService). That adapter is what a navigation app tells
 * about its guidance and what the cluster and launcher listen to, so DiAuto reports as one would.
 *
 * Nothing here is validated on the vehicle: the transaction codes come from the IMapService of the
 * MG4 Urban build and the interface descriptor is checked before anything is sent. The adapter fans
 * every call out synchronously while holding a lock, so all calls run on one background thread and
 * a slow cluster never waits on the main thread. After three failed calls in a row it stops until
 * the service reconnects.
 */
internal object SaicNavigationOutputs {
    private const val TAG = "DiAuto-SAIC"
    private const val PACKAGE = "com.saicmotor.adapterservice"
    private const val CLASS = "com.saicmotor.adapterservice.services.MapService"
    private const val DESCRIPTOR = "com.saicmotor.adapterservice.IMapService"
    private const val GENERAL_CLASS = "com.saicmotor.adapterservice.services.GeneralService"
    private const val GENERAL_DESCRIPTOR = "com.saicmotor.adapterservice.IGeneralService"
    private const val TX_GET_DISTANCE_UNIT = 28
    private const val MAX_FAILURES = 3
    private const val FRESH_NS = 30_000_000_000L

    private const val TX_MAP_NAVIGATING = 22
    private const val TX_REQUEST_PROJECTION = 27
    private const val TX_GUIDE_STATUS = 29
    private const val TX_GUIDE_INFOS = 30
    private const val TX_ROAD_INFO = 31
    private const val TX_REMAINING_TIME = 38
    private const val TX_REMAINING_DISTANCE = 39

    private val latest = AtomicReference<SaicGuidance?>()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diauto-saic-ipk").apply { isDaemon = true }
    }

    @Volatile private var binder: IBinder? = null
    @Volatile private var generalBinder: IBinder? = null
    @Volatile private var binding = false
    @Volatile private var disabled = false
    private var sent: SaicGuidance? = null // only touched on the executor thread
    private var failures = 0
    private var scheduled = false

    fun available(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    @Synchronized fun start(context: Context) {
        if (!available(context)) return
        connect(context.applicationContext)
        if (scheduled) return
        scheduled = true
        executor.scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { AppLog.w("$TAG: tick failed: ${it.message}") } },
            1, 1, TimeUnit.SECONDS
        )
    }

    fun update(value: SaicGuidance?) { latest.set(value) }

    /** Ends guidance on the cluster: the closing calls go out right away. */
    fun stop() {
        latest.set(null)
        executor.execute { runCatching { tick() } }
    }

    private fun connect(context: Context) {
        if (binder?.isBinderAlive == true || binding) return
        binding = true
        val intent = Intent().setComponent(ComponentName(PACKAGE, CLASS))
        val ok = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok) {
            binding = false
            AppLog.w("$TAG: bindService refused")
        }
        // Only for the unit the car is set to: without it the distance goes out in meters.
        if (generalBinder?.isBinderAlive != true) {
            val general = Intent().setComponent(ComponentName(PACKAGE, GENERAL_CLASS))
            runCatching { context.bindService(general, generalConnection, Context.BIND_AUTO_CREATE) }
        }
    }

    private val generalConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
            generalBinder = service.takeIf { descriptor == GENERAL_DESCRIPTOR }
            if (generalBinder == null) AppLog.w("$TAG: unexpected interface '$descriptor' - distances stay in meters")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            generalBinder = null
        }
    }

    /** The unit the car is set to (1 metric, 2 imperial); metric when the adapter does not say. */
    private fun distanceUnit(): Int {
        val target = generalBinder?.takeIf { it.isBinderAlive } ?: return SaicDistance.UNIT_METRIC
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(GENERAL_DESCRIPTOR)
            target.transact(TX_GET_DISTANCE_UNIT, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            AppLog.w("$TAG: reading the distance unit failed: ${e.message}")
            SaicDistance.UNIT_METRIC
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
            if (descriptor != DESCRIPTOR) {
                AppLog.w("$TAG: unexpected interface '$descriptor' - guidance will not be sent to the cluster")
                disabled = true
                return
            }
            binder = service
            disabled = false
            failures = 0
            executor.execute { sent = null } // a reconnected adapter has heard nothing
            AppLog.i("$TAG: connected to the map adapter")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            AppLog.w("$TAG: map adapter disconnected")
        }
    }

    private fun tick() {
        val target = binder?.takeIf { it.isBinderAlive && !disabled } ?: return
        val now = System.nanoTime()
        val value = latest.get()?.takeIf { now - it.updatedNs < FRESH_NS }
        for (call in SaicCallPlan.plan(sent, value)) {
            if (!send(target, call)) {
                if (++failures >= MAX_FAILURES) {
                    AppLog.w("$TAG: $MAX_FAILURES calls failed in a row - giving up until the adapter reconnects")
                    disabled = true
                }
                return // retried on the next tick, from the same previous state
            }
            failures = 0
        }
        sent = value
    }

    private fun send(target: IBinder, call: SaicCall): Boolean {
        val code: Int
        val write: (Parcel) -> Unit
        when (call) {
            is SaicCall.MapNavigating -> { code = TX_MAP_NAVIGATING; write = { it.writeInt(if (call.on) 1 else 0) } }
            is SaicCall.RequestProjection -> { code = TX_REQUEST_PROJECTION; write = { } }
            is SaicCall.GuideStatus -> { code = TX_GUIDE_STATUS; write = { it.writeInt(call.status) } }
            is SaicCall.GuideInfos -> {
                code = TX_GUIDE_INFOS
                val distance = SaicDistance.forCluster(call.distanceMeters, distanceUnit())
                write = { it.writeInt(call.icon.id); it.writeInt(distance); it.writeString(call.text) }
            }
            is SaicCall.RoadInfo -> { code = TX_ROAD_INFO; write = { it.writeString(call.road) } }
            is SaicCall.RemainingDistance -> { code = TX_REMAINING_DISTANCE; write = { it.writeInt(call.meters) } }
            is SaicCall.RemainingTime -> { code = TX_REMAINING_TIME; write = { it.writeInt(call.minutes) } }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            val ok = target.transact(code, data, reply, 0)
            if (ok) reply.readException()
            AppLog.d("$TAG: $call -> ${if (ok) "ok" else "refused"}")
            ok
        } catch (e: Exception) {
            AppLog.w("$TAG: $call failed: ${e.message}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
