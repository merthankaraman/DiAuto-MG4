package com.allgo.app.androidauto;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.util.Log;

import com.andrerinas.openheadunit.aap.AllgoMediaKeys;

/** What callers of AllGo's Android Auto ask for, done by DiAuto (this app now carries AllGo's name). */
final class DiAutoLink {
    private static final String PROJECTION = "com.andrerinas.openheadunit.aap.AapProjectionActivity";
    private static final String MAIN = "com.andrerinas.openheadunit.main.MainActivity";

    private DiAutoLink() {
    }

    /**
     * IAndroidAutoMainService.sendMediaPlayControl: 1 play, 2 pause, 3 next, 4 previous; the
     * launcher's press/release codes are in AllgoMediaKeys.
     */
    static void mediaControl(Context context, int code) {
        if (AllgoMediaKeys.fromRui(context, code)) return;
        MediaController controller = diAutoSession(context);
        if (controller == null) {
            Log.w(RelayState.TAG, "media control " + code + ": no DiAuto media session");
            return;
        }
        MediaController.TransportControls t = controller.getTransportControls();
        switch (code) {
            case 1: t.play(); break;
            case 2: t.pause(); break;
            case 3: t.skipToNext(); break;
            case 4: t.skipToPrevious(); break;
            default: Log.i(RelayState.TAG, "media control " + code + " ignored"); return;
        }
        Log.i(RelayState.TAG, "media control " + code + " sent to DiAuto");
    }

    /**
     * IAndroidAutoMainService.launchApp, sent by the car's shortcuts (the steering wheel phone key
     * goes through SystemUI's shortCutPhone → rui launchApp(3) while Android Auto is connected):
     * the Android Auto screen comes up, and for phone or music the phone opens that app.
     */
    static void launchApp(Context context, int app) {
        Log.i(RelayState.TAG, "launch app " + app);
        openScreen(context);
        if (!RelayState.isActive()) return;
        int key;
        switch (app) {
            case 3:
                AllgoMediaKeys.lastLaunchPhoneMs = android.os.SystemClock.elapsedRealtime();
                key = android.view.KeyEvent.KEYCODE_CALL;
                break;
            case 1: case 4: key = android.view.KeyEvent.KEYCODE_MUSIC; break;
            default: return;
        }
        // Once the projection is in front, so the phone's answer is seen.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> AllgoMediaKeys.click(context, key, "allgo-launch-app"), 600);
    }

    /** IAndroidAutoMainService.launchGoogleAssistant: as the steering wheel voice key. */
    static void launchAssistant(Context context) {
        Log.i(RelayState.TAG, "launch assistant");
        if (!RelayState.isActive()) return;
        AllgoMediaKeys.click(context, android.view.KeyEvent.KEYCODE_SEARCH, "allgo-assistant");
    }

    private static MediaController diAutoSession(Context context) {
        try {
            MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
            for (MediaController c : msm.getActiveSessions(null)) {
                if (context.getPackageName().equals(c.getPackageName())) return c;
            }
        } catch (Exception e) {
            Log.w(RelayState.TAG, "media sessions unreadable: " + e);
        }
        return null;
    }

    /** The Android Auto screen: DiAuto's projection while a phone is connected, else its home. */
    static void openScreen(Context context) {
        Intent intent = new Intent().setClassName(context, RelayState.isActive() ? PROJECTION : MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(RelayState.TAG, "open DiAuto failed: " + e);
            context.startActivity(new Intent().setClassName(context, MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
