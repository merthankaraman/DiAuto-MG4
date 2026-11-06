package com.andrerinas.openheadunit.ota;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Installs the downloaded APK with a PackageInstaller session, as MG4Dash does on the car.
 *
 * The session carries INSTALL_INTERNAL, the flag behind {@code pm install -f}: without it the MG4
 * refuses non-system apps on the internal storage, which is why the previous session fallback
 * failed. Android checks the signature itself and kills DiAuto at the end of the install;
 * {@link OtaPackageReplacedReceiver} then restarts the service and, after an update started
 * from the screen, the screen. {@code pm install} is only a fallback when the session cannot be
 * created.
 */
final class OtaInstaller {

    private static final String TAG = "DiAuto_OTA";
    /** PackageManager.INSTALL_INTERNAL, hidden. */
    private static final int INSTALL_INTERNAL = 0x00000010;

    private OtaInstaller() {
    }

    static void install(Context context, File apk) throws Exception {
        validateApk(context, apk);

        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()
                && !isSystemUid()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            settings.setData(Uri.parse("package:" + context.getPackageName()));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            throw new IllegalStateException("unknown sources permission required");
        }

        OtaPackageReplacedReceiver.markUpdateFromScreen(context);
        try {
            installWithSession(context, apk);
            Log.i(TAG, "PackageInstaller session committed, size=" + apk.length());
            return;
        } catch (Throwable t) {
            Log.w(TAG, "session install failed: " + t.getMessage());
            if (!isSystemUid()) {
                OtaPackageReplacedReceiver.clearUpdateFromScreen(context);
                throw t instanceof Exception ? (Exception) t : new IllegalStateException(t);
            }
        }
        try {
            installWithPm(apk);
        } catch (Exception e) {
            OtaPackageReplacedReceiver.clearUpdateFromScreen(context);
            throw e;
        }
    }

    private static boolean isSystemUid() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    private static void validateApk(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || info.packageName == null) {
            throw new IllegalStateException("PackageManager cannot parse APK");
        }
        if (!context.getPackageName().equals(info.packageName)) {
            throw new IllegalStateException("APK package mismatch: " + info.packageName);
        }
        long newCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), 0);
        long oldCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (newCode <= oldCode) {
            throw new IllegalStateException("APK versionCode " + newCode + " is not newer than " + oldCode);
        }
        Log.i(TAG, "APK ok versionName=" + info.versionName + " versionCode=" + newCode);
    }

    private static void installWithSession(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        params.setInstallLocation(PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        if (isSystemUid()) {
            try {
                Field flags = PackageInstaller.SessionParams.class.getField("installFlags");
                flags.setInt(params, flags.getInt(params) | INSTALL_INTERNAL);
            } catch (Throwable t) {
                Log.w(TAG, "INSTALL_INTERNAL flag: " + t.getMessage());
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            Intent callback = new Intent(context, OtaInstallResultReceiver.class);
            callback.setAction(OtaInstallResultReceiver.ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, callback, flags);
            session.commit(pi.getIntentSender());
            session = null; // commit owns it
        } finally {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void installWithPm(File apk) throws Exception {
        // -r replace, -f internal storage (see INSTALL_INTERNAL above).
        java.lang.Process process = new ProcessBuilder("pm", "install", "-r", "-f", apk.getAbsolutePath())
                .redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }
        int code = process.waitFor();
        String result = out.toString().trim();
        Log.i(TAG, "pm install exit=" + code + " output=" + result);
        if (code != 0 || !result.toLowerCase(java.util.Locale.US).contains("success")) {
            throw new IllegalStateException(result.isEmpty() ? ("pm install exit " + code) : result);
        }
    }
}
