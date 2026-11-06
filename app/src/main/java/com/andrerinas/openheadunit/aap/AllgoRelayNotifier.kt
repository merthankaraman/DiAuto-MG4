package com.andrerinas.openheadunit.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.allgo.app.androidauto.RelayState
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper

/**
 * DiAuto carries AllGo's package name and answers rui itself
 * (com.allgo.app.androidauto.AndroidAutoMainService): this tells that service when a session starts
 * or ends, so the stock launcher's Android Auto tile and media card see a connected phone.
 *
 * The car's Settings then keep the Bluetooth hands-free link of the announced MAC only and cut
 * every other phone's, so the MAC must be the phone's own: with the car's MAC the phone was cut and
 * reconnected every few seconds, and the sound stuttered with it.
 */
object AllgoRelayNotifier {
    /** The phone's MAC for this session, from the AA Bluetooth pairing request or the wireless link. */
    @Volatile
    private var sessionPhoneAddress: String? = null

    fun phoneAddressSeen(address: String?) {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address.uppercase())) return
        if (address.equals(sessionPhoneAddress, ignoreCase = true)) return
        AppLog.i("AllgoRelay: phone Bluetooth address known")
        sessionPhoneAddress = address.uppercase()
        RelayState.phoneMac(sessionPhoneAddress)
    }

    fun sessionStarted(context: Context, phoneName: String?, fallbackMac: String?) {
        // Music over Bluetooth: the car's Settings drop the phone's A2DP (and refuse it back) for
        // as long as rui reports an Android Auto session, so the music fell back to the phone's
        // speaker. Not announcing the session keeps Bluetooth music, which the car then shows and
        // controls as its own Bluetooth source.
        if (com.andrerinas.openheadunit.utils.Settings(context).musicViaBluetooth) {
            AppLog.i("AllgoRelay: music over Bluetooth — session not announced to the car, so it keeps A2DP")
            return
        }
        RelayState.load(context)
        RelayState.update(
            true, phoneName,
            sessionPhoneAddress ?: connectedPhone(context) ?: bondedNamed(context, phoneName) ?: fallbackMac,
        )
    }

    /** The paired phone with the session's name (its link may be down: the car cut it). */
    @SuppressLint("MissingPermission")
    private fun bondedNamed(context: Context, phoneName: String?): String? = try {
        if (phoneName.isNullOrBlank()) null
        else BluetoothHelper.getBluetoothAdapter(context)?.bondedDevices
            ?.singleOrNull { it.name == phoneName }?.address
    } catch (e: Exception) {
        null
    }

    fun sessionEnded(context: Context) {
        sessionPhoneAddress = null
        RelayState.load(context)
        RelayState.update(false, null, null)
    }

    @SuppressLint("MissingPermission")
    private fun connectedPhone(context: Context): String? = try {
        BluetoothHelper.getBluetoothAdapter(context)?.bondedDevices?.firstOrNull { dev ->
            runCatching { dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean }.getOrNull() == true
        }?.address
    } catch (e: Exception) {
        null
    }
}
