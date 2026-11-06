package com.andrerinas.openheadunit.ota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.main.MainActivity;

/**
 * Uygulama OTA ile yenilendikten sonra İndirilenler’deki eski APK’ları siler.
 * The install kills the old process, so the service is restarted here; otherwise Android Auto
 * stays down until the next car start. After an update started from DiAuto's screen, the
 * screen comes back too. No recompile from here: this process already runs the new code, and a
 * forced dexopt while it is mapped fails on the MG4 and deletes the odex the install produced
 * (the app then runs from the APK).
 */
public final class OtaPackageReplacedReceiver extends BroadcastReceiver {

    private static final String TAG = "DiAuto_OTA";
    private static final String PREFS_NAME = "diauto_ota";
    private static final String KEY_UPDATE_FROM_SCREEN = "updateFromScreenAt";
    private static final long UPDATE_FROM_SCREEN_MS = 10 * 60_000L;

    /** An update is being installed from the screen: show it again once installed. */
    static void markUpdateFromScreen(Context context) {
        context.getSharedPreferences(PREFS_NAME, 0).edit()
                .putLong(KEY_UPDATE_FROM_SCREEN, System.currentTimeMillis()).commit();
    }

    static void clearUpdateFromScreen(Context context) {
        context.getSharedPreferences(PREFS_NAME, 0).edit().remove(KEY_UPDATE_FROM_SCREEN).apply();
    }

    /**
     * DiAuto carries AllGo's package: the update killed the process rui (the car's Android Auto
     * service) was bound to, and rui does not bind again by itself, so the car stops seeing
     * Android Auto until it restarts. Ending rui's process (same system uid) makes the system start
     * it again, and it binds to this new DiAuto at once.
     */
    private static void restartRui(Context context) {
        try {
            android.app.ActivityManager am = context.getSystemService(android.app.ActivityManager.class);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if ("com.allgo.rui".equals(p.processName)) {
                    android.os.Process.killProcess(p.pid);
                    Log.i(TAG, "rui restarted after the update");
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "rui restart failed: " + t.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Log.i(TAG, "MY_PACKAGE_REPLACED → eski OTA APK temizliği");
        OtaCleanup.deleteAllOtaApks(context);
        try {
            ContextCompat.startForegroundService(context, new Intent(context, AapService.class));
            Log.i(TAG, "AapService restarted after the update");
        } catch (Throwable t) {
            Log.w(TAG, "AapService restart failed: " + t.getMessage());
        }
        restartRui(context);
        long at = context.getSharedPreferences(PREFS_NAME, 0).getLong(KEY_UPDATE_FROM_SCREEN, 0L);
        clearUpdateFromScreen(context);
        if (at > 0L && System.currentTimeMillis() - at < UPDATE_FROM_SCREEN_MS) {
            try {
                context.startActivity(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Log.i(TAG, "screen reopened after the update");
            } catch (Throwable t) {
                Log.w(TAG, "screen reopen failed: " + t.getMessage());
            }
        }
    }
}
