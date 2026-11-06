package com.andrerinas.openheadunit.ota;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OtaUpdateManager {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final long PROGRESS_INTERVAL_MS = 250L;

    private OtaUpdateManager() {
    }

    interface CheckCallback {
        void onResult(UpdateInfo info);
    }

    interface DownloadCallback {
        void onProgress(long done, long total);

        /** {@code apk} is the verified file, or null with {@code error}. */
        void onResult(File apk, String error);
    }

    interface InstallCallback {
        /** {@code error} is null once the install is handed to Android. */
        void onResult(String error);
    }

    static final class UpdateInfo {
        final boolean success;
        final boolean updateAvailable;
        final String currentVersion;
        final String latestVersion;
        final String releaseName;
        final String releaseNotes;
        final String downloadUrl;
        final String assetFileName;
        final String expectedSha256;
        final String message;
        final boolean prerelease;
        final long assetSize;

        UpdateInfo(
                boolean success,
                boolean updateAvailable,
                String currentVersion,
                String latestVersion,
                String releaseName,
                String releaseNotes,
                String downloadUrl,
                String assetFileName,
                String expectedSha256,
                String message,
                boolean prerelease
        ) {
            this(success, updateAvailable, currentVersion, latestVersion, releaseName, releaseNotes,
                    downloadUrl, assetFileName, expectedSha256, message, prerelease, 0L);
        }

        UpdateInfo(
                boolean success,
                boolean updateAvailable,
                String currentVersion,
                String latestVersion,
                String releaseName,
                String releaseNotes,
                String downloadUrl,
                String assetFileName,
                String expectedSha256,
                String message,
                boolean prerelease,
                long assetSize
        ) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.releaseName = releaseName;
            this.releaseNotes = releaseNotes;
            this.downloadUrl = downloadUrl;
            this.assetFileName = assetFileName;
            this.expectedSha256 = expectedSha256;
            this.message = message;
            this.prerelease = prerelease;
            this.assetSize = assetSize;
        }
    }

    static void checkForUpdates(Context context, boolean allowBetaUpdates, CheckCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            UpdateInfo info = OtaReleaseFetcher.fetchLatestRelease(appContext, allowBetaUpdates);
            MAIN_HANDLER.post(() -> callback.onResult(info));
        });
    }

    static void download(Context context, UpdateInfo info, DownloadCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            final long[] lastPost = {0L};
            try {
                File apk = OtaDownloader.download(appContext, info, (done, total) -> {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPost[0] < PROGRESS_INTERVAL_MS) return;
                    lastPost[0] = now;
                    MAIN_HANDLER.post(() -> callback.onProgress(done, total));
                });
                MAIN_HANDLER.post(() -> callback.onResult(apk, null));
            } catch (Exception e) {
                String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                MAIN_HANDLER.post(() -> callback.onResult(null, error));
            }
        });
    }

    /** Writing a 30 MB session takes a moment: kept off the main thread. */
    static void install(Context context, File apk, InstallCallback callback) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            String error = null;
            try {
                OtaInstaller.install(appContext, apk);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final String result = error;
            MAIN_HANDLER.post(() -> callback.onResult(result));
        });
    }
}
