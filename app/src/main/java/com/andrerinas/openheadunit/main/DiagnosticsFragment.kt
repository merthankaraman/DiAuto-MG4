package com.andrerinas.openheadunit.main

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.diag.CrashReportSender
import com.andrerinas.openheadunit.utils.DiagnosticExportStore
import com.andrerinas.openheadunit.utils.DiagnosticReport
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsFragment : Fragment(R.layout.fragment_diagnostics) {
    private var exporting = false
    /** Built before the file picker opens, so the picked file is written straight away. */
    private var preparedReport: String? = null
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) export(uri)
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.save_diagnostic_report).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) export() else chooseLocation()
        }
        view.findViewById<View>(R.id.choose_diagnostic_location).setOnClickListener { chooseLocation() }
    }

    private fun fileName() = "DiAuto-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    // The picker creates the (empty) file the moment one is chosen, so collect the logs first:
    // otherwise a USB stick pulled during collection keeps a 0-byte report.
    private fun chooseLocation() {
        if (exporting) return
        exporting = true
        val context = requireContext().applicationContext
        val button = view?.findViewById<MaterialButton>(R.id.save_diagnostic_report)
        button?.apply { isEnabled = false; text = getString(R.string.diagnostic_saving_report) }
        view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = false
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { runCatching { DiagnosticReport.build(context) } }
            exporting = false
            if (!isAdded || view == null) return@launch
            button?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
            view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = true
            preparedReport = report.getOrNull() ?: return@launch showSaveFailure()
            runCatching { destination.launch(fileName()) }.onFailure {
                preparedReport = null
                Toast.makeText(requireContext(), getString(R.string.diagnostic_file_picker_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun export(destination: Uri? = null) {
        if (exporting) return
        exporting = true
        val context = requireContext().applicationContext
        val fileName = fileName()
        val button = view?.findViewById<MaterialButton>(R.id.save_diagnostic_report)
        button?.apply { isEnabled = false; text = getString(R.string.diagnostic_saving_report) }
        view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = false
        lifecycleScope.launch {
            var reportText: String? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = preparedReport ?: DiagnosticReport.build(context)
                    preparedReport = null
                    reportText = report
                    if (destination != null) {
                        try {
                            DiagnosticExportStore.write(context.contentResolver, destination, report)
                        } catch (error: Exception) {
                            // Don't leave the picker's empty file behind looking like a report.
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                            throw error
                        }
                        destination
                    } else {
                        check(Build.VERSION.SDK_INT >= 29)
                        DiagnosticExportStore.saveToDownloads(context.contentResolver, fileName, report)
                    }
                }
            }
            exporting = false
            if (!isAdded || view == null) return@launch
            button?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
            view?.findViewById<View>(R.id.choose_diagnostic_location)?.isEnabled = true
            result.onSuccess {
                val text = reportText.orEmpty()
                val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(getString(R.string.diagnostic_report_saved))
                    .setMessage(
                        if (destination == null) "Downloads/DiAuto/$fileName"
                        else getString(R.string.diagnostic_saved_to_your_selected_location)
                    )
                    .setPositiveButton(R.string.crash_report_send, null)
                    .setNegativeButton(getString(R.string.diagnostic_done), null)
                    .create()
                dialog.setOnShowListener {
                    val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    sendButton.setOnClickListener {
                        if (text.isBlank()) {
                            Toast.makeText(requireContext(), R.string.crash_report_empty, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        sendButton.isEnabled = false
                        Toast.makeText(requireContext(), R.string.crash_report_sending, Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val sendResult = CrashReportSender.send(
                                report = text,
                                fileName = fileName,
                                caption = "DiAuto diagnostic: $fileName",
                            )
                            withContext(Dispatchers.Main) {
                                if (!isAdded) return@withContext
                                if (sendResult.ok) {
                                    Toast.makeText(requireContext(), R.string.crash_report_sent, Toast.LENGTH_LONG).show()
                                    dialog.dismiss()
                                } else {
                                    sendButton.isEnabled = true
                                    Toast.makeText(
                                        requireContext(),
                                        R.string.crash_report_send_failed,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }.onFailure { showSaveFailure() }
        }
    }

    private fun showSaveFailure() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.diagnostic_could_not_save_the_report))
            .setMessage(getString(R.string.diagnostic_save_failure_hint))
            .setPositiveButton(getString(R.string.diagnostic_choose_location)) { _, _ -> chooseLocation() }
            .setNegativeButton(R.string.close, null).show()
    }
}
