package com.allgo.app.androidauto;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * Answers com.allgo.rui as AllGo's Android Auto service would (IAndroidAutoMainService, read from
 * rui's own proxy), with DiAuto's session as the connected phone. DiAuto is installed as an update of
 * com.allgo.app.androidauto, so rui, the stock launcher and MG4 Dash find it under AllGo's name.
 */
public class AndroidAutoMainService extends Service {

    private static final String DESCRIPTOR = "com.allgo.app.androidauto.IAndroidAutoMainService";

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Apps outside the car's own that may still drive the session (MG4 Dash's media controls). */
    private static final String[] ALLOWED_PACKAGES = { "app.mg4dash" };

    /**
     * Only the car's own apps (system ids, or signed with the car's platform key like rui and the
     * launcher) and MG4 Dash: this service hands out the phone's name and Bluetooth address and
     * drives the assistant and the media, so any other app installed on the car is refused.
     */
    private boolean callerAllowed(int uid) {
        if (uid < android.os.Process.FIRST_APPLICATION_UID || uid == android.os.Process.myUid()) return true;
        android.content.pm.PackageManager pm = getPackageManager();
        try {
            if (pm.checkSignatures(android.os.Process.myUid(), uid) == android.content.pm.PackageManager.SIGNATURE_MATCH) return true;
            String[] packages = pm.getPackagesForUid(uid);
            if (packages != null) {
                for (String pkg : packages) {
                    for (String allowed : ALLOWED_PACKAGES) if (allowed.equals(pkg)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(RelayState.TAG, "caller check failed: " + e);
        }
        return false;
    }

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            int uid = Binder.getCallingUid();
            if (!callerAllowed(uid)) {
                Log.w(RelayState.TAG, "call " + code + " refused for uid " + uid);
                reply.writeException(new SecurityException("not a car app"));
                return true;
            }
            Log.i(RelayState.TAG, "call " + code + " from uid " + uid);
            switch (code) {
                case 1: // register(deviceCb, sessionCb)
                    RelayState.register(data.readStrongBinder(), data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                case 8: // unregister(deviceCb, sessionCb)
                    RelayState.unregister(data.readStrongBinder(), data.readStrongBinder());
                    reply.writeNoException();
                    return true;
                case 6: // getActiveDevice()
                    reply.writeNoException();
                    if (RelayState.isActive()) {
                        reply.writeInt(1);
                        RelayState.writeDevice(reply);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                case 7: // isSessionActive()
                case 24: // getVRStatus()
                    reply.writeNoException();
                    reply.writeInt(code == 7 && RelayState.isActive() ? 1 : 0);
                    return true;
                case 2: // startSession(device, screenMode, audioMode)
                case 3: // resumeSession(device)
                    handler.postDelayed(RelayState::sessionRequested, 300);
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                case 25: { // sendMediaPlayControl(code)
                    int key = data.readInt();
                    handler.post(() -> DiAutoLink.mediaControl(AndroidAutoMainService.this, key));
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
                }
                case 10: { // launchGoogleAssistant(boolean)
                    data.readInt();
                    handler.post(() -> DiAutoLink.launchAssistant(AndroidAutoMainService.this));
                    reply.writeNoException();
                    reply.writeInt(RelayState.isActive() ? 0 : -1);
                    return true;
                }
                case 11: { // launchApp(app): 0 home, 1 music, 2 navigation, 3 phone, 4 now playing
                    int app = data.readInt();
                    handler.post(() -> DiAutoLink.launchApp(AndroidAutoMainService.this, app));
                    reply.writeNoException();
                    reply.writeInt(RelayState.isActive() ? 0 : -1);
                    return true;
                }
                case 12: // getPluginVersion()
                    reply.writeNoException();
                    reply.writeString("diauto-relay-1.0");
                    return true;
                case 9: case 19: case 20: case 21: case 22: case 23: // void calls
                    reply.writeNoException();
                    return true;
                default: // every other call returns an int status: 0 = OK
                    Log.i(RelayState.TAG, "rui call " + code + " answered OK");
                    reply.writeNoException();
                    reply.writeInt(0);
                    return true;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        RelayState.load(this);
        RuiRepair.run(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(RelayState.TAG, "bound by " + intent);
        RuiRepair.run(this);
        return binder;
    }
}
