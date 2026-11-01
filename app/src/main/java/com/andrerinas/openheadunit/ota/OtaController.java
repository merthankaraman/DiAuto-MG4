package com.andrerinas.openheadunit.ota;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.andrerinas.openheadunit.R;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * GitHub Releases üzerinden OTA: kontrol → indir (SHA-256) → PackageInstaller.
 * Used by the home screen button and by Settings; both share the last check.
 */
public final class OtaController {

    private static final String PREFS_NAME = "diauto_ota";
    private static final String KEY_ALLOW_BETA = "allowBetaUpdates";
    private static final long RECHECK_MS = 30 * 60_000L;

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }

    /** What the home screen shows. */
    public static final class Status {
        public final State state;
        /** Newest version offered when {@link #state} is AVAILABLE. */
        public final String latestVersion;
        public final boolean prerelease;
        public final boolean allowBeta;

        public Status(State state, String latestVersion, boolean prerelease, boolean allowBeta) {
            this.state = state;
            this.latestVersion = latestVersion;
            this.prerelease = prerelease;
            this.allowBeta = allowBeta;
        }
    }

    public interface StatusListener {
        void onOtaStatus(String statusText, boolean updateAvailable, boolean checking);
    }

    public interface StateListener {
        void onOtaState(Status status);
    }

    // Last check, shared by every screen of the process.
    private static OtaUpdateManager.UpdateInfo sLastInfo;
    private static boolean sLastAllowBeta;
    private static long sLastCheckAt;
    /** A download or an install is running. */
    private static boolean sBusy;
    /** The screen that started it, told when Android refuses the install. */
    private static WeakReference<OtaController> sActive = new WeakReference<>(null);

    private final FragmentActivity activity;
    private StatusListener statusListener;
    private StateListener stateListener;
    private Dialog progressDialog;
    private TextView progressStatus;
    private View progressRetry;
    private boolean checking;
    private boolean answerWhenChecked;
    private boolean stopped;

    public OtaController(FragmentActivity activity) {
        this.activity = activity;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    /** Settings / UI without dedicated Button/CheckBox views. */
    public void bindWithoutViews() {
        render();
    }

    public boolean isAllowBeta() {
        return prefs().getBoolean(KEY_ALLOW_BETA, false);
    }

    public void setAllowBeta(boolean allow) {
        prefs().edit().putBoolean(KEY_ALLOW_BETA, allow).apply();
        sLastInfo = null;
        render();
    }

    /** Offers the update already found, otherwise checks first. */
    public void checkOrDownload() {
        if (sBusy) {
            OtaDialogs.showMessageDialog(activity, activity.getString(R.string.ota_progress_busy));
            return;
        }
        OtaUpdateManager.UpdateInfo info = currentInfo();
        if (info != null && info.success && info.updateAvailable) {
            confirmInstall(info);
            return;
        }
        check(true);
    }

    /** Silent check, unless one was made less than 30 minutes ago with the same beta choice. */
    public void checkOnStartup() {
        if (currentInfo() != null && SystemClock.elapsedRealtime() - sLastCheckAt < RECHECK_MS) {
            render();
            return;
        }
        check(false);
    }

    public void stop() {
        stopped = true;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS_NAME, 0);
    }

    private OtaUpdateManager.UpdateInfo currentInfo() {
        return sLastInfo != null && sLastAllowBeta == isAllowBeta() ? sLastInfo : null;
    }

    /** Called by {@link OtaInstallResultReceiver} when the session failed (DiAuto still runs). */
    static void onInstallFailed(String message) {
        sBusy = false;
        OtaController controller = sActive.get();
        if (controller != null) {
            controller.showFailure(controller.progressStatus, controller.progressRetry,
                    controller.activity.getString(R.string.ota_install_failed_status, message));
        }
    }

    private void check(boolean interactive) {
        // A tap during the silent check gets its answer when that check returns.
        answerWhenChecked |= interactive;
        if (checking) return;
        final boolean allowBeta = isAllowBeta();
        checking = true;
        render();
        OtaUpdateManager.checkForUpdates(activity, allowBeta, info -> {
            checking = false;
            sLastInfo = info;
            sLastAllowBeta = allowBeta;
            sLastCheckAt = SystemClock.elapsedRealtime();
            if (stopped) return;
            render();
            boolean answer = answerWhenChecked;
            answerWhenChecked = false;
            if (!answer) return;
            if (info != null && info.success && info.updateAvailable) {
                confirmInstall(info);
            } else if (info != null && info.success) {
                boolean noStable = activity.getString(R.string.ota_status_no_stable).equals(info.message);
                OtaDialogs.showMessageDialog(activity, activity.getString(noStable
                        ? R.string.ota_dialog_no_stable_message
                        : R.string.ota_dialog_up_to_date_version, displayVersion(info.currentVersion)));
            } else {
                OtaDialogs.showMessageDialog(activity,
                        activity.getString(R.string.ota_dialog_check_failed_message));
            }
        });
    }

    private void confirmInstall(OtaUpdateManager.UpdateInfo info) {
        if (info.expectedSha256 == null || info.expectedSha256.trim().isEmpty()) {
            OtaDialogs.showMessageDialog(activity, info.message != null && !info.message.trim().isEmpty()
                    ? info.message
                    : activity.getString(R.string.ota_error_no_hash));
            return;
        }
        String size = info.assetSize > 0L ? formatBytes(info.assetSize) : "?";
        OtaDialogs.showConfirmDialog(
                activity,
                OtaDialogs.withDataWarning(
                        activity.getString(R.string.ota_data_warning, size),
                        activity.getString(R.string.ota_dialog_install_message, info.latestVersion, size)),
                activity.getString(R.string.ota_action_install_update),
                () -> startDownload(info)
        );
    }

    private void startDownload(OtaUpdateManager.UpdateInfo info) {
        sBusy = true;
        sActive = new WeakReference<>(this);
        OtaDialogs.ProgressDialogHandle handle = OtaDialogs.showProgressDialog(
                activity,
                activity.getString(R.string.ota_dialog_download_started_message, info.latestVersion),
                () -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    startDownload(info);
                },
                null
        );
        progressDialog = handle.dialog;
        progressStatus = handle.statusView;
        progressRetry = handle.retryButton;
        final TextView status = handle.statusView;
        final ProgressBar bar = handle.progressBar;
        final View retry = handle.retryButton;

        OtaUpdateManager.download(activity, info, new OtaUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(long done, long total) {
                if (stopped) return;
                long expected = total > 0L ? total : info.assetSize;
                if (bar != null && expected > 0L) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(100L, done * 100L / expected));
                }
                if (status != null) {
                    status.setText(expected > 0L
                            ? activity.getString(R.string.ota_progress_downloading,
                                    (int) Math.min(100L, done * 100L / expected), formatBytes(done), formatBytes(expected))
                            : activity.getString(R.string.ota_progress_running_unknown, formatBytes(done)));
                }
            }

            @Override
            public void onResult(java.io.File apk, String error) {
                if (error != null) {
                    sBusy = false;
                    showFailure(status, retry, activity.getString(R.string.ota_progress_failed_reason, error));
                    return;
                }
                if (!stopped) {
                    if (bar != null) {
                        bar.setIndeterminate(false);
                        bar.setProgress(100);
                    }
                    if (status != null) status.setText(R.string.ota_progress_verified);
                }
                // The user asked for it: installed even if the screen was left meanwhile.
                OtaUpdateManager.install(activity, apk, installError -> {
                    if (installError != null) {
                        sBusy = false;
                        showFailure(status, retry,
                                activity.getString(R.string.ota_install_failed_status, installError));
                        return;
                    }
                    if (!stopped && status != null) status.setText(R.string.ota_progress_installing);
                });
            }
        });
    }

    private void showFailure(TextView status, View retry, String text) {
        if (stopped) return;
        if (status != null) status.setText(text);
        if (retry != null) retry.setVisibility(View.VISIBLE);
    }

    /** "2.0.0-beta15" for the "2.0.0-beta15-debug" build. */
    private static String displayVersion(String version) {
        if (version == null) return "";
        return version.replace("-debug", "").replace("-phone", "");
    }

    private void render() {
        boolean allowBeta = isAllowBeta();
        OtaUpdateManager.UpdateInfo info = currentInfo();
        State state;
        String text;
        if (checking) {
            state = State.CHECKING;
            text = activity.getString(R.string.ota_status_checking);
        } else if (info == null) {
            state = State.IDLE;
            text = activity.getString(R.string.ota_status_idle);
        } else if (info.success && info.updateAvailable) {
            state = State.AVAILABLE;
            text = activity.getString(R.string.ota_status_update_available, info.latestVersion);
        } else if (info.success) {
            state = State.UP_TO_DATE;
            text = info.message != null ? info.message : activity.getString(R.string.ota_status_up_to_date);
        } else {
            state = State.FAILED;
            text = activity.getString(R.string.ota_status_check_failed_detail,
                    info.message != null ? info.message : "");
        }
        if (statusListener != null) {
            statusListener.onOtaStatus(text, state == State.AVAILABLE, state == State.CHECKING);
        }
        if (stateListener != null) {
            stateListener.onOtaState(new Status(state,
                    info != null ? info.latestVersion : null,
                    info != null && info.prerelease, allowBeta));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
