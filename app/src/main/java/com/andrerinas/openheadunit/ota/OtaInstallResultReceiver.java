package com.andrerinas.openheadunit.ota;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;

/**
 * PackageInstaller session sonucu.
 */
public final class OtaInstallResultReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_RESULT = "com.andrerinas.openheadunit.OTA_INSTALL_RESULT";
    private static final String TAG = "DiAuto_OTA";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "install result status=" + status + " msg=" + message);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Throwable t) {
                        Log.e(TAG, "confirm UI: " + t.getMessage());
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                OtaCleanup.deleteAllOtaApks(context);
                Toast.makeText(context, R.string.ota_install_success, Toast.LENGTH_LONG).show();
                break;
            default:
                OtaPackageReplacedReceiver.clearUpdateFromScreen(context);
                OtaController.onInstallFailed(message != null ? message : String.valueOf(status));
                Toast.makeText(
                        context,
                        context.getString(R.string.ota_install_failed_status,
                                message != null ? message : String.valueOf(status)),
                        Toast.LENGTH_LONG
                ).show();
                break;
        }
    }
}
