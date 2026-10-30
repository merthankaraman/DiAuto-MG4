package com.andrerinas.openheadunit.diag

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * One-tap report upload for the head-unit user (no login, no share sheet).
 * Delivery channel is Telegram; credentials are baked in at build time.
 */
object CrashReportSender {

    data class Result(val ok: Boolean, val error: String? = null)

    /** Generated at compile time; read reflectively so IDE does not require a prior build. */
    private fun buildConfigString(field: String): String = runCatching {
        Class.forName("com.andrerinas.openheadunit.BuildConfig")
            .getField(field)
            .get(null) as String
    }.getOrDefault("")

    fun send(
        report: String,
        fileName: String = "diauto_crash.txt",
        caption: String = titleFromCrash(report),
    ): Result {
        val token = buildConfigString("TELEGRAM_BOT_TOKEN")
        val chatId = buildConfigString("TELEGRAM_CHAT_ID")
        if (token.isBlank() || chatId.isBlank()) {
            return Result(ok = false, error = "unavailable")
        }
        val bytes = report.toByteArray(StandardCharsets.UTF_8)
        val safeName = fileName.replace("\"", "").ifBlank { "diauto_report.txt" }
        val safeCaption = caption.take(200)

        return try {
            val boundary = "DiautoCrash" + System.currentTimeMillis()
            val url = URL("https://api.telegram.org/bot$token/sendDocument")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 25_000
                readTimeout = 45_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "DiAuto-MG4-CrashReporter")
            }

            DataOutputStream(connection.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(StandardCharsets.UTF_8))
                    out.writeBytes("\r\n")
                }

                writeField("chat_id", chatId)
                writeField("caption", safeCaption)

                out.writeBytes("--$boundary\r\n")
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"document\"; filename=\"$safeName\"\r\n"
                )
                out.writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                out.write(bytes)
                out.writeBytes("\r\n")
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()

            if (code in 200..299) {
                val ok = runCatching { JSONObject(response).optBoolean("ok", false) }.getOrDefault(false)
                if (ok) Result(ok = true)
                else Result(ok = false, error = "telegram_rejected")
            } else {
                val description = runCatching {
                    JSONObject(response).optString("description")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
                Result(ok = false, error = description)
            }
        } catch (e: Exception) {
            Result(ok = false, error = e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    fun shareReport(context: Context, report: String, fileName: String): Boolean {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, report)
        }
        val chooser = Intent.createChooser(sendIntent, "Share $fileName")
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun titleFromCrash(report: String): String {
        val exceptionLine = report.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.contains("Exception") ||
                    line.contains("Error") ||
                    line.startsWith("java.") ||
                    line.startsWith("kotlin.") ||
                    line.startsWith("android.")
            }
        return "DiAuto crash: ${(exceptionLine ?: "report").take(160)}"
    }
}
