package com.andrerinas.openheadunit.main

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
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
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** The car's standby screen. Wireless pairing is the primary path; USB remains a fallback. */
class HomeFragment : Fragment() {
    private val app get() = App.provide(requireContext())
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) connectWireless()
        else showBluetoothHelp(R.string.bt_permission_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<View>(R.id.wifi_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else connectWireless()
        }
        view.findViewById<View>(R.id.usb_button).setOnClickListener {
            if (app.commManager.isConnected) openProjection()
            else findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        // Going back to the car does not tear down a connection or disable reconnect.
        view.findViewById<View>(R.id.exit_button).setOnClickListener { requireActivity().moveTaskToBack(true) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.commManager.connectionState.collect { state ->
                    val status = when (state) {
                        is CommManager.ConnectionState.Disconnected -> R.string.da_ready
                        is CommManager.ConnectionState.TransportStarted -> R.string.da_connected
                        else -> R.string.da_connecting
                    }
                    view.findViewById<TextView>(R.id.connection_status).setText(status)
                    view.findViewById<MaterialButton>(R.id.wifi_button).setText(
                        if (app.commManager.isConnected) R.string.da_open else R.string.da_connect)
                }
            }
        }
        if (app.settings.autoStartOnBoot || app.settings.autoStartOnScreenOn) {
            ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
        }
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
        if (app.settings.wifiConnectionMode == 1) {
            connectHeadunitServer()
            return
        }
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

    /**
     * Headunit Server mode reaches the phone over Wi-Fi only, so Bluetooth is not required here.
     * A phone that is linked anyway is remembered, so its next Bluetooth connection starts the
     * scan by itself (AutoStartReceiver only acts on remembered phones).
     */
    private fun connectHeadunitServer() {
        try {
            val connected = BluetoothHelper.connectedBondedDevices(requireContext())
            if (connected.size == 1) rememberAutoStartDevice(connected[0])
        } catch (_: SecurityException) {
            // Remembering the phone is a convenience; the scan below does not need it.
        }
        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_START_WIRELESS_SCAN
        })
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
        app.settings.wifiConnectionMode = 3
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

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    companion object {
        // Retained for existing automation entry points; self projection has no standby control.
        var forceSelfModeLaunch = false
        fun resetAutoStart() { forceSelfModeLaunch = false }
    }
}
