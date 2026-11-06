package com.andrerinas.openheadunit.main

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.vehicle.BatteryCapacityResolver
import com.andrerinas.openheadunit.vehicle.Mg4DashBattery
import com.andrerinas.openheadunit.vehicle.Mg4EnergyProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** MG4 → Google Maps battery / charging settings (separate from Display). */
class ChargingInfoFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingBatteryForGoogleMaps: Boolean? = null
    private var pendingBatteryDemoMode: Boolean? = null
    private var pendingBatteryDemoPercent: Int? = null
    private var pendingBatteryDemoRangeKm: Int? = null
    private var pendingBatteryNetCapacityKwh: Float? = null
    private var pendingBatteryStateOfHealthPercent: Float? = null
    private var pendingCapacityManual: Boolean? = null
    private var pendingSohManual: Boolean? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001
    private val uiHandler = Handler(Looper.getMainLooper())
    private val liveRefresh = object : Runnable {
        override fun run() {
            if (!isAdded) return
            // Only refresh list when showing live car rows (demo off).
            if (pendingBatteryForGoogleMaps == true && pendingBatteryDemoMode != true) {
                updateSettingsList()
            }
            uiHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_charging_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings

        pendingBatteryForGoogleMaps = settings.batteryForGoogleMaps
        pendingBatteryDemoMode = settings.batteryDemoMode
        pendingBatteryDemoPercent = settings.batteryDemoPercent
        pendingBatteryDemoRangeKm = settings.batteryDemoRangeKm
        pendingBatteryNetCapacityKwh = settings.batteryNetCapacityKwh
        pendingBatteryStateOfHealthPercent = settings.batteryStateOfHealthPercent
        pendingCapacityManual = settings.batteryCapacityManual
        pendingSohManual = settings.batterySohManual

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackPress()
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        // Start reader so live rows can fill even before Save.
        Mg4EnergyProvider.start(requireContext())
        Mg4EnergyProvider.refresh()

        updateSettingsList()
        setupToolbar()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(liveRefresh)
        uiHandler.post(liveRefresh)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(liveRefresh)
        super.onPause()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBackPress() }
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener { saveSettings() }
        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ -> findNavController().navigateUp() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().navigateUp()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = getString(R.string.save)
    }

    private fun saveSettings() {
        pendingBatteryForGoogleMaps?.let { settings.batteryForGoogleMaps = it }
        pendingBatteryDemoMode?.let { settings.batteryDemoMode = it }
        pendingBatteryDemoPercent?.let { settings.batteryDemoPercent = it }
        pendingBatteryDemoRangeKm?.let { settings.batteryDemoRangeKm = it }
        pendingBatteryNetCapacityKwh?.let { settings.batteryNetCapacityKwh = it }
        pendingBatteryStateOfHealthPercent?.let { settings.batteryStateOfHealthPercent = it }
        pendingCapacityManual?.let { settings.batteryCapacityManual = it }
        pendingSohManual?.let { settings.batterySohManual = it }
        if (settings.batteryForGoogleMaps) {
            Mg4EnergyProvider.start(requireContext())
            Mg4EnergyProvider.refresh()
        }
        hasChanges = false
        updateSaveButtonState()
        updateSettingsList()
        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun checkChanges() {
        hasChanges = pendingBatteryForGoogleMaps != settings.batteryForGoogleMaps ||
            pendingBatteryDemoMode != settings.batteryDemoMode ||
            pendingBatteryDemoPercent != settings.batteryDemoPercent ||
            pendingBatteryDemoRangeKm != settings.batteryDemoRangeKm ||
            pendingBatteryNetCapacityKwh != settings.batteryNetCapacityKwh ||
            pendingBatteryStateOfHealthPercent != settings.batteryStateOfHealthPercent ||
            pendingCapacityManual != settings.batteryCapacityManual ||
            pendingSohManual != settings.batterySohManual
        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "batteryForGoogleMaps",
            nameResId = R.string.battery_for_google_maps,
            descriptionResId = R.string.battery_for_google_maps_description,
            isChecked = pendingBatteryForGoogleMaps ?: true,
            onCheckedChanged = { enabled ->
                pendingBatteryForGoogleMaps = enabled
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingBatteryForGoogleMaps == true) {
            val resolved = previewBattery()
            val auto = previewBattery(manual = false)

            items.add(SettingItem.SettingEntry(
                stableId = "batteryNetCapacityKwh",
                nameResId = R.string.battery_net_capacity_kwh,
                value = capacityText(resolved),
                onClick = {
                    showBatteryCapacityDialog(resolved, auto)
                }
            ))
            items.add(SettingItem.SettingEntry(
                stableId = "batteryStateOfHealthPercent",
                nameResId = R.string.battery_soh_percent,
                value = sohText(resolved),
                onClick = {
                    showDecimalInputDialog(
                        title = getString(R.string.battery_soh_percent),
                        message = getString(R.string.battery_soh_description) + "\n\n" +
                            getString(R.string.battery_soh_auto_description),
                        initialValue = resolved.sohPercent,
                        neutralText = getString(R.string.battery_auto),
                        onNeutral = {
                            pendingSohManual = false
                            checkChanges()
                            updateSettingsList()
                        },
                    ) { value ->
                        pendingBatteryStateOfHealthPercent = value.coerceIn(1f, 100f)
                        pendingSohManual = true
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))
            items.add(SettingItem.SettingEntry(
                stableId = "batteryEffectiveCapacity",
                nameResId = R.string.battery_effective_capacity,
                value = getString(R.string.battery_effective_capacity_value, resolved.effectiveKwh),
                onClick = {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.battery_effective_capacity)
                        .setMessage(R.string.battery_soh_description)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            ))

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "batteryDemoMode",
                nameResId = R.string.battery_demo_mode,
                descriptionResId = R.string.battery_demo_mode_description,
                isChecked = pendingBatteryDemoMode ?: false,
                onCheckedChanged = { enabled ->
                    pendingBatteryDemoMode = enabled
                    checkChanges()
                    updateSettingsList()
                }
            ))

            if (pendingBatteryDemoMode == true) {
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "batteryDemoPercent",
                    nameResId = R.string.battery_demo_percent,
                    value = "${pendingBatteryDemoPercent ?: 72}%",
                    sliderValue = (pendingBatteryDemoPercent ?: 72).toFloat(),
                    valueFrom = 1f,
                    valueTo = 100f,
                    stepSize = 1f,
                    onValueChanged = { value ->
                        pendingBatteryDemoPercent = value.toInt()
                        checkChanges()
                        updateSettingsList()
                    }
                ))
                items.add(SettingItem.SettingEntry(
                    stableId = "batteryDemoRangeKm",
                    nameResId = R.string.battery_demo_range_km,
                    value = getString(R.string.battery_value_km, pendingBatteryDemoRangeKm ?: 280),
                    onClick = {
                        showNumericInputDialog(
                            title = getString(R.string.battery_demo_enter_range),
                            initialValue = pendingBatteryDemoRangeKm ?: 280,
                        ) { km ->
                            pendingBatteryDemoRangeKm = km.coerceAtLeast(1)
                            checkChanges()
                            updateSettingsList()
                        }
                    }
                ))
            } else {
                addLiveCarRows(items)
            }
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun addLiveCarRows(items: MutableList<SettingItem>) {
        items.add(SettingItem.CategoryHeader("batteryLiveFromCar", R.string.battery_live_from_car))

        val resolved = previewBattery()
        val snap = Mg4EnergyProvider.readCarSnapshot(requireContext(), resolved.netKwh, resolved.sohPercent)
            ?: Mg4EnergyProvider.snapshot()?.takeIf { !it.demo }

        if (snap != null) {
            items.add(SettingItem.SettingEntry(
                stableId = "batteryLiveSoc",
                nameResId = R.string.battery_live_soc,
                value = "${snap.batteryPercent.roundToInt()}%",
                onClick = { },
            ))
            items.add(SettingItem.SettingEntry(
                stableId = "batteryLiveRange",
                nameResId = R.string.battery_live_range,
                value = getString(R.string.battery_value_km, snap.rangeKm),
                onClick = { },
            ))
            items.add(SettingItem.SettingEntry(
                stableId = "batteryLiveEnergy",
                nameResId = R.string.battery_live_energy,
                value = getString(
                    R.string.battery_live_energy_value,
                    snap.currentWh / 1000.0,
                    snap.capacityWh / 1000.0,
                ),
                onClick = { },
            ))
        } else {
            // Phone desk APK: no SWI telemetry. Car: first read may still be pending.
            val status = if (Mg4EnergyProvider.carHardwarePresent()) {
                getString(R.string.battery_live_waiting)
            } else {
                getString(R.string.battery_live_unavailable)
            }
            items.add(SettingItem.InfoBanner(
                stableId = "batteryLiveStatus",
                textResId = R.string.battery_live_waiting,
                text = status,
            ))
        }

        items.add(SettingItem.ActionButton(
            stableId = "batteryLiveRefresh",
            textResId = R.string.battery_live_refresh,
            onClick = {
                Mg4EnergyProvider.start(requireContext())
                Mg4EnergyProvider.refresh()
                updateSettingsList()
            },
        ))
    }

    private fun showNumericInputDialog(title: String, initialValue: Int, onConfirm: (Int) -> Unit) {
        val context = requireContext()
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialValue.toString())
        }
        val container = android.widget.FrameLayout(context)
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        container.addView(
            editView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(margin, 8, margin, 8) },
        )
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm((editView.text.toString().toIntOrNull() ?: initialValue).coerceAtLeast(1))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDecimalInputDialog(
        title: String,
        message: String?,
        initialValue: Float,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        onConfirm: (Float) -> Unit,
    ) {
        val context = requireContext()
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatDecimal(initialValue))
        }
        val container = android.widget.FrameLayout(context)
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        container.addView(
            editView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(margin, 8, margin, 8) },
        )
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newVal = editView.text.toString().replace(',', '.').toFloatOrNull() ?: initialValue
                onConfirm(newVal)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (neutralText != null) setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() } }
            .show()
    }

    /** First choice: automatic, then the three MG4 batteries. */
    private fun showBatteryCapacityDialog(
        resolved: BatteryCapacityResolver.Resolved,
        auto: BatteryCapacityResolver.Resolved,
    ) {
        val packs = BatteryCapacityResolver.PACKS
        val labels = listOf(
            getString(R.string.battery_auto_option, getString(R.string.battery_value_kwh, auto.netKwh), capacitySource(auto))
        ) + packs.map { (pack, usable) -> getString(R.string.battery_pack_option, pack, usable) }
        val selectedIndex = if (resolved.netSource != BatteryCapacityResolver.Source.MANUAL) 0
            else 1 + packs.indices.minByOrNull { kotlin.math.abs(packs[it].second - resolved.netKwh) }!!
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.battery_net_capacity_kwh)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                if (which == 0) {
                    pendingCapacityManual = false
                } else {
                    pendingBatteryNetCapacityKwh = packs[which - 1].second
                    pendingCapacityManual = true
                }
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Values the screen shows: the pending manual choices, or what automatic finds now. */
    private fun previewBattery(manual: Boolean = true): BatteryCapacityResolver.Resolved =
        BatteryCapacityResolver.resolve(
            manualNetKwh = pendingBatteryNetCapacityKwh.takeIf { manual && pendingCapacityManual == true },
            manualSohPercent = pendingBatteryStateOfHealthPercent.takeIf { manual && pendingSohManual == true },
            carKwh = BatteryCapacityResolver.carCapacityKwh(),
            mg4Dash = Mg4DashBattery.read(requireContext()),
        )

    private fun capacityText(resolved: BatteryCapacityResolver.Resolved) = batteryCapacityText(requireContext(), resolved)

    private fun capacitySource(resolved: BatteryCapacityResolver.Resolved) = batteryCapacitySource(requireContext(), resolved)

    private fun sohText(resolved: BatteryCapacityResolver.Resolved) = batterySohText(requireContext(), resolved)

    private fun formatDecimal(value: Float): String = formatBatteryDecimal(value)
}

/** "61.0 kWh · automatic (64 kWh battery by default)", or the value set by hand. */
internal fun batteryCapacityText(context: Context, resolved: BatteryCapacityResolver.Resolved): String {
    val kwh = context.getString(R.string.battery_value_kwh, resolved.netKwh)
    return if (resolved.netSource == BatteryCapacityResolver.Source.MANUAL) {
        context.getString(R.string.battery_manual_value, kwh)
    } else {
        context.getString(R.string.battery_auto_value, kwh, batteryCapacitySource(context, resolved))
    }
}

internal fun batteryCapacitySource(context: Context, resolved: BatteryCapacityResolver.Resolved): String =
    when (resolved.netSource) {
        BatteryCapacityResolver.Source.CAR -> context.getString(R.string.battery_source_car)
        else -> context.getString(
            R.string.battery_source_default_pack, resolved.pack ?: BatteryCapacityResolver.DEFAULT_PACK)
    }

internal fun batterySohText(context: Context, resolved: BatteryCapacityResolver.Resolved): String =
    when (resolved.sohSource) {
        BatteryCapacityResolver.Source.MANUAL -> context.getString(
            R.string.battery_soh_manual_value,
            context.getString(R.string.battery_value_percent, formatBatteryDecimal(resolved.sohPercent)),
        )
        BatteryCapacityResolver.Source.MEASURED -> context.getString(
            R.string.battery_auto_value,
            context.getString(R.string.battery_value_percent,
                String.format(java.util.Locale.getDefault(), "%.1f", resolved.sohPercent)),
            context.getString(R.string.battery_soh_source_measured),
        )
        // No figure to show yet: Maps is told BatteryCapacityResolver.DEFAULT_SOH meanwhile.
        else -> context.getString(
            R.string.battery_soh_measuring,
            com.andrerinas.openheadunit.vehicle.BatteryCapacityMeter.progressPercent(context).toInt(),
            com.andrerinas.openheadunit.vehicle.BatteryCapacityMeter.MIN_DROP.toInt(),
        )
    }

/** 98.75 → "98,75" in French; up to two decimals, as typed. */
private fun formatBatteryDecimal(value: Float): String =
    java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()).apply {
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value.toDouble())
