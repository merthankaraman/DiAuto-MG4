package com.andrerinas.openheadunit.main

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.ota.OtaController
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** The car's standby screen. Wireless pairing is the primary path; USB remains a fallback. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var ota: OtaController? = null
    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) connectWireless()
        else showBluetoothHelp(R.string.bt_permission_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.da_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<TextView>(R.id.home_version).text =
            BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-phone")
        FirmwareCompat.warning(requireContext(), com.evsuite.hardware.FirmwareInfo.getDetectedString())?.let { text ->
            view.findViewById<TextView>(R.id.home_firmware_warning).apply {
                this.text = text
                visibility = View.VISIBLE
            }
        }
        view.findViewById<View>(R.id.home_main_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else connectWireless()
        }
        view.findViewById<View>(R.id.home_settings).setOnClickListener {
            startActivity(DaSettingsActivity.intent(requireContext()))
        }
        view.findViewById<View>(R.id.home_trip).setOnClickListener {
            TripDialogs.toggle(requireActivity()) { showTripTile() }
        }
        val updateHint = view.findViewById<TextView>(R.id.home_update_hint)
        ota = OtaController(requireActivity()).also { controller ->
            controller.setStateListener { status -> updateHint.text = updateText(requireContext(), status) }
        }
        view.findViewById<View>(R.id.home_update).setOnClickListener { ota?.checkOrDownload() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.commManager.connectionState.collect { state ->
                    val connected = state is CommManager.ConnectionState.TransportStarted
                    val phone = app.settings.autoStartBluetoothDeviceName
                    view.findViewById<TextView>(R.id.home_state).text = when {
                        connected && phone.isNotBlank() -> getString(R.string.dhome_connected, phone)
                        connected -> getString(R.string.dhome_connected_generic)
                        state is CommManager.ConnectionState.Disconnected -> getString(R.string.dhome_waiting)
                        else -> getString(R.string.dhome_connecting)
                    }
                    view.findViewById<TextView>(R.id.home_state_hint).setText(
                        if (connected) R.string.dhome_connected_hint else R.string.dhome_waiting_hint)
                    view.findViewById<TextView>(R.id.home_main_label).setText(
                        if (app.commManager.isConnected) R.string.da_open else R.string.da_connect)
                }
            }
        }
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }
    }

    private fun showTripTile() {
        val view = view ?: return
        val on = App.provide(requireContext()).settings.tripRecording
        view.findViewById<View>(R.id.home_trip).visibility = if (on) View.VISIBLE else View.GONE
        if (on) view.findViewById<TextView>(R.id.home_trip_label).setText(TripDialogs.buttonLabel(requireActivity()))
    }

    private fun openProjection() {
        startActivity(AapProjectionActivity.intent(requireContext()).apply {
            putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        })
    }

    /**
     * Prefer the phone already linked over Bluetooth — no picker. Only ask when several phones
     * are connected at once and none is the remembered auto-start device.
     */
    private fun connectWireless() {
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        try {
            if (adapter == null || !adapter.isEnabled) {
                showBluetoothHelp(R.string.da_bluetooth_off)
                return
            }
            val bonded = adapter.bondedDevices.orEmpty()
            if (bonded.isEmpty()) {
                showBluetoothHelp(R.string.da_no_paired)
                return
            }
            if (BluetoothHelper.anyConnectedDeviceState(requireContext()) == false) {
                showBluetoothHelp(R.string.da_no_connected_bluetooth)
                return
            }

            val connected = BluetoothHelper.connectedBondedDevices(requireContext())
            val preferredMacs = app.settings.autoStartBluetoothDeviceMacs
            val preferredConnected = connected.filter { preferredMacs.contains(it.address) }

            val target = when {
                preferredConnected.size == 1 -> preferredConnected[0]
                preferredConnected.isEmpty() && connected.size == 1 -> connected[0]
                preferredConnected.size > 1 -> null // ambiguous preferred set
                connected.size > 1 -> null
                // Per-device isConnected unavailable: fall back to a single preferred bonded MAC.
                preferredMacs.size == 1 -> bonded.firstOrNull { it.address == preferredMacs.first() }
                else -> null
            }

            if (target != null) {
                startNativeAa(target)
                return
            }

            // Ambiguous: only offer currently connected phones when we can see them.
            val choices = if (connected.isNotEmpty()) connected else bonded.sortedBy { it.name ?: it.address }
            activeDialog?.dismiss()
            activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.da_connect)
                .setItems(choices.map { it.name ?: getString(R.string.da_device) }.toTypedArray()) { _, index ->
                    startNativeAa(choices[index])
                }
                .setNeutralButton(R.string.da_pair) { _, _ -> openBluetoothSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (_: SecurityException) {
            showBluetoothHelp(R.string.bt_permission_denied)
        }
    }
    private fun rememberAutoStartDevice(device: BluetoothDevice) {
        val macs = app.settings.autoStartBluetoothDeviceMacs
        if (!macs.contains(device.address)) {
            val updated = macs + device.address
            app.settings.autoStartBluetoothDeviceMacs = updated
            app.settings.autoStartBluetoothDeviceName = device.name ?: "Unknown Device"
            Settings.syncAutoStartBtMacsToDeviceStorage(requireContext(), updated)
        }
    }

    private fun startNativeAa(device: BluetoothDevice) {
        // Remember this phone so the next Bluetooth ACL_CONNECTED auto-starts without a tap.
        rememberAutoStartDevice(device)
        (activity as? MainActivity)?.beginAutoConnect(
            "native wireless ${device.address}", MainActivity.ConnectionUiMode.PILL)
        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, device.address)
        })
    }

    private fun showBluetoothHelp(message: Int) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.da_pair).setMessage(message)
            .setPositiveButton(R.string.da_system_settings) { _, _ -> openBluetoothSettings() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun openBluetoothSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        showTripTile()
        // Also after Settings, where the beta option may have changed.
        ota?.checkOnStartup()
    }

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    override fun onDestroyView() {
        ota?.stop()
        ota = null
        super.onDestroyView()
    }
}

/** The update tile's line: the version to install when one is offered, else where things stand. */
internal fun updateText(context: android.content.Context, status: OtaController.Status): String {
    val version = BuildConfig.VERSION_NAME.removeSuffix("-debug").removeSuffix("-phone")
    if (status.state == OtaController.State.AVAILABLE) {
        return context.getString(R.string.da_update_install, status.latestVersion)
    }
    return when (status.state) {
        OtaController.State.CHECKING -> context.getString(R.string.da_update_hint_checking)
        OtaController.State.AVAILABLE -> context.getString(
            if (status.prerelease) R.string.da_update_hint_available_beta else R.string.da_update_hint_available)
        OtaController.State.UP_TO_DATE -> context.getString(
            if (status.allowBeta) R.string.da_update_hint_up_to_date_beta else R.string.da_update_hint_up_to_date,
            version)
        OtaController.State.FAILED -> context.getString(R.string.da_update_hint_failed, version)
        else -> context.getString(R.string.da_update_hint_current, version)
    }
}
