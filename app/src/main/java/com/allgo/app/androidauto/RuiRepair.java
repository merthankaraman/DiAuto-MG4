package com.allgo.app.androidauto;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * rui stores every Android Auto device it is told about, keyed by its Bluetooth address, and
 * crashes at start if one of them is not a valid address. The first relay sent an empty one;
 * this removes such entries from rui's own preferences (same system uid). rui reads them again
 * on its next start.
 */
final class RuiRepair {
    private static final String RUI = "com.allgo.rui";
    private static final String PREFS = "com.allgo.rui.DeviceSharedPref_ver_1";
    private static final String MASTER = "com.allgo.rui.DeviceSharedPref.MasterKey";

    private RuiRepair() {
    }

    static void run(Context context) {
        try {
            Context rui = context.createPackageContext(RUI, 0);
            SharedPreferences prefs = rui.getSharedPreferences(PREFS, 0);
            Set<String> stored = prefs.getStringSet(MASTER, null);
            if (stored == null) return;
            Set<String> kept = new HashSet<>();
            SharedPreferences.Editor edit = prefs.edit();
            boolean changed = false;
            for (String key : stored) {
                if (key != null && BluetoothAdapter.checkBluetoothAddress(key)) {
                    kept.add(key);
                } else if (key != null && key.length() > 0 && !key.contains(":")) {
                    kept.add(key); // USB serial ids are not addresses
                } else {
                    edit.remove(key == null ? "" : key);
                    changed = true;
                    Log.w(RelayState.TAG, "removed invalid rui device key '" + key + "'");
                }
            }
            if (changed) {
                edit.putStringSet(MASTER, kept).commit();
            }
        } catch (Exception e) {
            Log.w(RelayState.TAG, "rui repair failed: " + e);
        }
    }

    /** A valid address for the device rui will store: DiAuto's, else a fixed local one. */
    private static final String FALLBACK_MAC = "02:00:00:D1:A0:70";

    static String validMac(String mac) {
        return isValidMac(mac) ? mac : FALLBACK_MAC;
    }

    static boolean isValidMac(String mac) {
        return mac != null && BluetoothAdapter.checkBluetoothAddress(mac.toUpperCase());
    }

    /** A real phone's MAC, not the stand-in used until one is known. */
    static boolean isPhoneMac(String mac) {
        return isValidMac(mac) && !FALLBACK_MAC.equalsIgnoreCase(mac);
    }
}
