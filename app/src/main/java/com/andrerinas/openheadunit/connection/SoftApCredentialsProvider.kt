package com.andrerinas.openheadunit.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.ApInterfaceCandidate
import com.andrerinas.openheadunit.aap.SoftApBssidPolicy
import com.andrerinas.openheadunit.aap.NativeCredentialsPolicy
import com.andrerinas.openheadunit.aap.SoftApCredentials
import com.andrerinas.openheadunit.aap.SoftApCredentialsAttempt
import com.andrerinas.openheadunit.aap.SoftApCredentialsPolicy
import com.andrerinas.openheadunit.aap.SoftApNetworkPolicy
import com.andrerinas.openheadunit.aap.SoftApState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HotspotConfigReader
import com.andrerinas.openheadunit.utils.HotspotManager
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import com.andrerinas.openheadunit.utils.NetworkAddresses
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SoftApStateReader
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Supplies the Native AA handshake with the credentials of *this head unit's own access point*,
 * as an alternative to [WifiDirectManager]'s P2P group.
 *
 * A teardown of the OEM ZLink app showed it doing wireless Android Auto over an ordinary WPA2 soft
 * AP — same Bluetooth handshake, same UUID, no WiFi Direct — so the phone accepts a plain access
 * point, and that route sidesteps the P2P failure modes behind most Native AA reports: group
 * churn, the self-wake loop, phones stuck on "Obtaining IP address".
 *
 * It does not copy ZLink's way of *starting* the AP; that needs TETHER_PRIVILEGED and root
 * daemons. Reading a hotspot the user configured is the load-bearing path, switching one on is
 * best effort. Same contract as [WifiDirectManager] so a launcher can treat both transports alike.
 */
class SoftApCredentialsProvider(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: Settings
) {

    companion object {
        /** How often to look for the access point while waiting for it to come up. */
        private const val POLL_INTERVAL_MS = 1_000L

        /** How long to keep looking before giving up and saying so. */
        private const val RESOLVE_BUDGET_MS = 30_000L

        /** How long to wait for a user-configured AP before trying to switch one on ourselves. */
        private const val AUTO_ENABLE_AFTER_MS = 5_000L

        /** Not in any public SDK constant: the soft AP state broadcast and its disabled state. */
        private const val WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLED = 13
    }

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null
    private var onInvalidated: (() -> Unit)? = null

    /** Answers phone connectivity checks so SoftAP is not abandoned for "no internet". */
    private val captivePortalSpoof = SoftApCaptivePortalSpoof()

    private var resolveJob: Job? = null
    /** Fences blocking interface/MAC reads from a resolver superseded by refresh or stop. */
    private var resolveGeneration = 0L
    @Volatile private var isRunning = false
    @Volatile private var isReceiverRegistered = false
    /** Whether *we* turned the hotspot on, and so may turn it back on if it drops. */
    @Volatile private var autoEnabled = false

    /**
     * Whether switching the hotspot on has already been tried since [start].
     *
     * A field rather than a local in [beginResolve], because [refresh] restarts that loop and the
     * handshake calls it every time it has been waiting too long — which used to re-arm auto-enable
     * on each one, so a slow access point got a second start sweep on top of the first, racing it
     * band for band. One attempt per run is the whole of the best effort on offer.
     */
    @Volatile private var triedAutoEnable = false

    /** So the once-per-second poll reports "nothing here" once, not thirty times. */
    @Volatile private var reportedNoInterface = false

    /**
     * When [start] was called, so the budget below measures the run rather than one look.
     *
     * [beginResolve] used to stamp its own deadline, and [refresh] restarts it — which the handshake
     * calls every ~10 s while it waits. A budget of 30 s restarted every 10 s never expires, so the
     * line that tells the user no access point could be found was unreachable in exactly the case it
     * was written for. Measured: an access point down for 3.5 minutes, the resolve polling the whole
     * time, and nothing said.
     */
    @Volatile private var runStartedAt = 0L

    /** So that budget is reported once per run, not once per [refresh]. */
    @Volatile private var reportedBudgetExhausted = false

    /**
     * Same idea for the unreadable-configuration dead end, but latched for the whole run rather
     * than per resolve: the handshake calls [refresh] every time it has been waiting too long, and
     * the phone redials every few seconds, so this would otherwise be said once per attempt for as
     * long as the user keeps trying. It is one instruction and it does not change.
     */
    @Volatile private var reportedConfigUnreadable = false

    private val apStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isRunning) return
            val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
            if (state == WIFI_AP_STATE_ENABLED) {
                AppLog.i("SoftApCredentials: The hotspot came up — resolving its current interface and credentials.")
                refresh()
                return
            }
            if (state != WIFI_AP_STATE_DISABLED) return
            AppLog.w("SoftApCredentials: The hotspot went down — the credentials the phone was given are no longer valid.")
            refresh()
            if (autoEnabled && isRunning) {
                AppLog.i("SoftApCredentials: Re-enabling the hotspot we started, once.")
                autoEnabled = false
                // Off the main thread: setHotspotEnabled waits for the access point to actually
                // come up, and onReceive() has an ANR budget measured in seconds.
                scope.launch(Dispatchers.IO + CoroutineName("SoftApCredentials-Reenable")) {
                    HotspotManager.setHotspotEnabled(context, true)
                    refresh()
                }
            }
        }
    }

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    fun setInvalidatedListener(callback: () -> Unit) {
        this.onInvalidated = callback
    }

    @Synchronized
    fun start() {
        if (isRunning) {
            refresh()
            return
        }
        isRunning = true
        // elapsedRealtime survives head-unit wall-clock jumps (seen as "after 245014311s").
        runStartedAt = SystemClock.elapsedRealtime()
        triedAutoEnable = false
        reportedConfigUnreadable = false
        reportedBudgetExhausted = false
        if (!isReceiverRegistered) {
            try {
                // A system broadcast, so EXPORTED: NOT_EXPORTED silently never fires on API 34+.
                ContextCompat.registerReceiver(
                    context, apStateReceiver, IntentFilter(WIFI_AP_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED
                )
                isReceiverRegistered = true
            } catch (e: Exception) {
                AppLog.w("SoftApCredentials: Could not watch the hotspot state: ${e.message}")
            }
        }
        beginResolve()
    }

    /** Look again, from the top. Called when the handshake has been waiting too long. */
    @Synchronized
    fun refresh() {
        if (!isRunning) return
        beginResolve()
    }

    @Synchronized
    fun stop() {
        isRunning = false
        resolveGeneration++
        resolveJob?.cancel()
        resolveJob = null
        autoEnabled = false
        triedAutoEnable = false
        reportedConfigUnreadable = false
        reportedBudgetExhausted = false
        captivePortalSpoof.stop()
        if (isReceiverRegistered) {
            // Reset even if unregister throws: a flag set in only one direction is how a
            // long-lived manager ends up unable to re-arm.
            try { context.unregisterReceiver(apStateReceiver) } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    /**
     * The one failure on this route the user can actually fix, so it is worth interrupting them
     * for: without it the whole symptom is a phone that connects over Bluetooth and then does
     * nothing, with the reason only in a log they have no reason to read.
     *
     * Forced past the toast preference, because a silent dead end is worse than an unwanted toast.
     * Posted to the main thread rather than switched to it with withContext: the resolve loop is
     * cancelled by [refresh] and by [stop], and a suspending hop would let that cancellation
     * swallow the message after the once-per-run latch had already been set.
     */
    private fun showConfigUnreadableToast() {
        Handler(Looper.getMainLooper()).post {
            ToastUtils.showToast(
                context,
                R.string.hotspot_config_unreadable_toast,
                Toast.LENGTH_LONG,
                force = true
            )
        }
    }

    private fun beginResolve() {
        val generation = ++resolveGeneration
        resolveJob?.cancel()
        // Re-resolving is also invalidation: retrying must wait for the new AP, rather than
        // sending the cached eth0 details again while this job looks for ap0.
        captivePortalSpoof.stop()
        onInvalidated?.invoke()
        reportedNoInterface = false
        resolveJob = scope.launch(Dispatchers.IO + CoroutineName("SoftApCredentials-Resolve")) {
            val deadline = SystemClock.elapsedRealtime() + RESOLVE_BUDGET_MS

            while (isActive && isRunning && SystemClock.elapsedRealtime() < deadline) {
                val chosen = pickApInterface()
                val apName = chosen?.iface?.name ?: "the access point"
                when (if (chosen == null) SoftApCredentialsAttempt.NO_AP_YET else publish(chosen, generation)) {
                    SoftApCredentialsAttempt.PUBLISHED -> return@launch

                    SoftApCredentialsAttempt.CONFIG_UNREADABLE -> {
                        // Waiting cannot help and the budget would be spent in silence, so stop and
                        // say the one thing that does. Observed on a unit whose hotspot was up and
                        // found the whole time: the resolve loop read "could not read its name" as
                        // "there is no hotspot", switched on an access point that was already on,
                        // and repeated the same line once a second until it gave up.
                        if (!reportedConfigUnreadable) {
                            reportedConfigUnreadable = true
                            AppLog.e(
                                "SoftApCredentials: The access point on $apName is up, but this " +
                                    "device will not let apps read its name, so there is nothing to hand the " +
                                    "phone. Waiting will not change that. Set 'Hotspot name (manual)' and " +
                                    "'Hotspot password (manual)' in Settings to this device's own hotspot name " +
                                    "and password, then connect again."
                            )
                            showConfigUnreadableToast()
                        }
                        invalidateIfCurrent(generation)
                        return@launch
                    }

                    SoftApCredentialsAttempt.NO_AP_YET -> {
                        // Nothing on air yet, which is exactly what auto-enable is for. Reached only
                        // here, so an access point that is up but unreadable never triggers it.
                        val waited = SystemClock.elapsedRealtime() - runStartedAt
                        reportBudgetExhaustedOnce(waited)
                        if (!triedAutoEnable && waited >= AUTO_ENABLE_AFTER_MS && settings.autoEnableHotspot) {
                            triedAutoEnable = true
                            AppLog.i("SoftApCredentials: No access point after ${waited / 1000}s — trying to switch this device's hotspot on.")
                            // Best effort; most unrooted units lack the permission. Keep polling
                            // anyway, since the user may switch it on by hand.
                            autoEnabled = HotspotManager.setHotspotEnabled(context, true)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }

            if (isActive && isRunning) {
                reportBudgetExhaustedOnce(SystemClock.elapsedRealtime() - runStartedAt, force = true)
                invalidateIfCurrent(generation)
            }
        }
    }

    @Synchronized
    private fun invalidateIfCurrent(generation: Long) {
        if (isRunning && generation == resolveGeneration) {
            captivePortalSpoof.stop()
            onInvalidated?.invoke()
        }
    }

    /**
     * Says once per run that this has been going on too long to be a hotspot still coming up.
     *
     * Reported rather than acted on: the polling continues, because the user switching the hotspot
     * on by hand is a real recovery and the only one left on a device where auto-enable cannot. What
     * this replaces is silence — the old message was tied to a deadline [beginResolve] restamped on
     * every [refresh], so on the path that needed it most it never printed at all.
     */
    private fun reportBudgetExhaustedOnce(waited: Long, force: Boolean = false) {
        if (reportedBudgetExhausted || (!force && waited < RESOLVE_BUDGET_MS)) return
        reportedBudgetExhausted = true
        AppLog.e(
            "SoftApCredentials: No usable access point after ${waited / 1000}s. " +
                "Turn this device's hotspot on before connecting — 5 GHz is strongly " +
                "recommended, Android Auto video is poor over 2.4 GHz — or switch the " +
                "Android Auto network transport back to WiFi Direct."
        )
    }

    /** The interface we settled on, and whether the user named it rather than us guessing. */
    private data class ChosenInterface(val iface: ApInterfaceCandidate, val namedByUser: Boolean)

    /**
     * The interface our access point is running on, if it is up.
     *
     * Every reference implementation of this protocol — aa-proxy-rs, the Raspberry Pi dongles, the
     * OEM ZLink app — either creates the access point itself or reads the interface name from
     * configuration; none of them infer it. We are the only one reading an access point we did not
     * create, on hardware we do not control, which is why [Settings.hotspotInterface] comes first
     * and the heuristic is the fallback.
     */
    private fun pickApInterface(): ChosenInterface? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList().map { nif ->
                ApInterfaceCandidate(
                    name = nif.name,
                    isLoopback = try { nif.isLoopback } catch (e: Exception) { false },
                    isUp = try { nif.isUp } catch (e: Exception) { false },
                    siteLocalIpv4 = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                        ?.hostAddress
                )
            }
        } catch (e: Exception) {
            AppLog.w("SoftApCredentials: Could not enumerate network interfaces: ${e.message}")
            return null
        }

        val named = settings.hotspotInterface.trim()
        if (named.isNotEmpty()) {
            val match = candidates.firstOrNull { it.name.equals(named, ignoreCase = true) }
            val picked = SoftApNetworkPolicy.pickApInterface(candidates, namedInterface = named)
            when {
                match == null ->
                    AppLog.w("SoftApCredentials: Waiting for interface '$named'. Present: ${candidates.joinToString { it.name }}.")
                picked == null ->
                    AppLog.w("SoftApCredentials: Waiting for interface '$named' to be up with a private IPv4 address.")
                else -> {
                    AppLog.i("SoftApCredentials: Using '${picked.name}' (${picked.siteLocalIpv4}) as named in settings.")
                    return ChosenInterface(picked, namedByUser = true)
                }
            }
            return null
        }

        val stationIpv4 = NetworkAddresses.stationIpv4(context)
        val eligible = SoftApNetworkPolicy.eligible(candidates, stationIpv4)
        val picked = SoftApNetworkPolicy.pickApInterface(candidates, stationIpv4) ?: run {
            // Rejecting everything is the correct answer when no hotspot is up, but saying nothing
            // for the whole 30s budget reads as a hang. Name what was there so the reader can pick
            // one for the override if the access point is on an interface we did not recognise.
            if (!reportedNoInterface) {
                reportedNoInterface = true
                AppLog.i(
                    "SoftApCredentials: No interface looks like an access point; waiting for one. " +
                        "Present: " + candidates.filter { it.isUp && !it.isLoopback }
                        .joinToString { "${it.name} (${it.siteLocalIpv4 ?: "no private address"})" }
                )
            }
            return null
        }
        if (eligible.size > 1) {
            // More than one survivor means the name decided it, which is a guess. Nothing else
            // reports having guessed, and picking wrong hands the phone an unreachable address.
            AppLog.w(
                "SoftApCredentials: More than one interface could be the access point — " +
                    eligible.joinToString { "${it.name} (${it.siteLocalIpv4})" } +
                    " — choosing ${picked.name} by name. Set the hotspot interface by hand if that is wrong."
            )
        }
        return ChosenInterface(picked, namedByUser = false)
    }

    /** Resolves the rest of the credentials for [chosen] and hands them over. */
    private suspend fun publish(chosen: ChosenInterface, generation: Long): SoftApCredentialsAttempt {
        val iface = chosen.iface
        val ip = iface.siteLocalIpv4 ?: return SoftApCredentialsAttempt.NO_AP_YET

        // Always try the live SoftAP configuration when the platform allows it (DiPlay): a
        // saved settings password goes stale when the vehicle UI changes the hotspot passphrase.
        // getSoftApConfiguration() is reflection over a non-public API and can refuse on a
        // locked-down device — SoftApCredentialsPolicy then falls back to the manual fields.
        val manualSsid = settings.hotspotSsid
        val manualPass = settings.hotspotPassword
        val systemConfig =
            HotspotConfigReader.getSystemHotspotConfig(context)?.let { SoftApCredentials(it.first, it.second) }

        // Diagnostic only — never log the passphrase itself.
        AppLog.i(
            "SoftApCredentials: resolve iface=${iface.name} ip=$ip " +
                "configReadable=${systemConfig != null} " +
                "liveSsid=${systemConfig?.ssid?.ifEmpty { "<empty>" } ?: "<unreadable>"} " +
                "savedSsid=${manualSsid.ifEmpty { "<empty>" }} " +
                "livePskLen=${systemConfig?.passphrase?.length ?: 0} " +
                "savedPskLen=${manualPass.length}"
        )

        if (SoftApCredentialsPolicy.liveSsidConflicts(manualSsid, systemConfig)) {
            // Live wins in resolve(); warn so a stale Connection-setup override can be cleared.
            AppLog.w(
                "SoftApCredentials: Saved hotspot name '$manualSsid' differs from live SoftAP " +
                    "'${systemConfig?.ssid}' — advertising the live name (clear the saved override " +
                    "in Connection setup if this is unexpected)."
            )
        }

        val attempt = SoftApCredentialsPolicy.decide(manualSsid, manualPass, systemConfig, ip)
        if (attempt != SoftApCredentialsAttempt.PUBLISHED) {
            AppLog.w("SoftApCredentials: decide=$attempt on ${iface.name} ($ip) — not publishing yet.")
            return attempt
        }
        val (ssid, psk) = SoftApCredentialsPolicy.resolve(manualSsid, manualPass, systemConfig)
        val liveSsid = !systemConfig?.ssid.isNullOrEmpty()
        val pskSource = when {
            !systemConfig?.passphrase.isNullOrEmpty() -> "live"
            manualPass.isNotEmpty() -> "saved"
            else -> "none"
        }
        AppLog.i(
            "SoftApCredentials: will advertise SSID=$ssid pskSource=$pskSource pskLen=${psk.length} " +
                "ssidSource=${if (liveSsid) "live" else "saved"}"
        )

        if (psk.isEmpty()) {
            AppLog.w("SoftApCredentials: No passphrase for '$ssid'. An open network will be refused by the phone; set one by hand if this fails.")
        }

        val apState = SoftApStateReader.read(context)
        AppLog.i("SoftApCredentials: SoftApState=$apState namedByUser=${chosen.namedByUser}")
        if (!NativeCredentialsPolicy.shouldPublishCredentials(apState, chosen.namedByUser)) {
            AppLog.w(
                "SoftApCredentials: the system reports no access point running, so ${iface.name} " +
                    "($ip) is some other network — a modem bridge or a wired link will look just " +
                    "like this. Not handing the phone a network that is not on air. Switch the " +
                    "hotspot on, or name the interface by hand if you know it is up."
            )
            return SoftApCredentialsAttempt.NO_AP_YET
        }
        if (apState == SoftApState.UNKNOWN) {
            AppLog.i("SoftApCredentials: This device does not let apps read the hotspot state; proceeding without confirming the access point is up.")
        }

        // Android Auto requires a BSSID in Type 3 (Paul / Pixel 8 Pro: empty BSSID → status=-3).
        // DiPlay can omit it on CarPlay; AA cannot. Prefer the current AP interface address over a
        // saved override: the latter may belong to a previous P2P group or hotspot after a restart.
        // Use the manual value only when all live interface readers are unavailable.
        val shellMac = InterfaceMacReader.read(iface.name)
        val hardwareAddress = hardwareAddressOf(iface.name)
        val ipv6DerivedMac = P2pInterfaceBssid.read(iface.name)
        val bssidSource = when {
            SoftApBssidPolicy.isUsable(shellMac) -> "sysfs"
            SoftApBssidPolicy.isUsable(hardwareAddress) -> "hardware"
            SoftApBssidPolicy.isUsable(ipv6DerivedMac) -> "ipv6-eui64"
            SoftApBssidPolicy.isUsable(settings.staticBSSID) -> "static-fallback"
            else -> "none"
        }
        val detectedBssid = SoftApBssidPolicy.choose(
            staticOverride = null,
            shellMac = shellMac,
            hardwareAddress = hardwareAddress,
            ipv6DerivedMac = ipv6DerivedMac,
        )
        if (SoftApBssidPolicy.isUsable(settings.staticBSSID) &&
            SoftApBssidPolicy.isUsable(detectedBssid) &&
            !SoftApBssidPolicy.sameAddress(settings.staticBSSID, detectedBssid)
        ) {
            AppLog.w(
                "SoftApCredentials: Saved Static BSSID does not match the live hotspot interface; " +
                    "using the current interface address. The saved value may belong to another " +
                    "wireless transport or an earlier hotspot."
            )
        }
        val bssid = SoftApBssidPolicy.choose(
            staticOverride = settings.staticBSSID,
            shellMac = shellMac,
            hardwareAddress = hardwareAddress,
            ipv6DerivedMac = ipv6DerivedMac,
        )
        AppLog.i(
            "SoftApCredentials: AP BSSID ${if (bssid.isEmpty()) "unavailable" else "ready"} " +
                "on ${iface.name} source=$bssidSource"
        )
        if (bssid.isEmpty()) {
            AppLog.w(
                "SoftApCredentials: No SoftAP BSSID for ${iface.name}. " +
                    "Android Auto usually refuses join (status=-3); set static BSSID in Advanced settings."
            )
        }

        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            if (!isRunning || generation != resolveGeneration) {
                SoftApCredentialsAttempt.NO_AP_YET
            } else {
                AppLog.i("SoftApCredentials: SUCCESS - Providing credentials from ${iface.name}: SSID=$ssid, IP=$ip, BSSID=${bssid.ifEmpty { "<none>" }}")
                // SoftAP has no upstream; spoof captive checks so phones do not drop the join.
                captivePortalSpoof.start(iface.name, ip)
                onCredentialsReady?.invoke(ssid, psk, ip, bssid)
                SoftApCredentialsAttempt.PUBLISHED
            }
        }
    }

    private fun hardwareAddressOf(name: String): String? = try {
        NetworkInterface.getByName(name)?.hardwareAddress
            ?.joinToString(":") { String.format("%02x", it) }
    } catch (_: Exception) {
        null
    }
}
