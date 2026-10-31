package com.andrerinas.openheadunit.ota;

import android.content.Context;
import android.util.Log;

import com.andrerinas.openheadunit.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Downloads the release APK into the app cache and checks it against the release's SHA-256.
 * Replaces DownloadManager, which wrote to the public Downloads folder: the installer then had
 * to find the file again, and MG4Dash's updater (plain download + PackageInstaller) is the path
 * known to work on the car.
 */
final class OtaDownloader {

    private static final String TAG = "DiAuto_OTA";
    static final String OTA_DIR = "ota";
    private static final String APK_NAME = "update.apk";
    private static final int MAX_REDIRECTS = 5;

    interface Progress {
        void onProgress(long done, long total);
    }

    private OtaDownloader() {
    }

    static File download(Context context, OtaUpdateManager.UpdateInfo info, Progress progress) throws Exception {
        if (info == null || info.downloadUrl == null || info.downloadUrl.isEmpty()) {
            throw new IllegalArgumentException("no APK download URL");
        }
        if (info.expectedSha256 == null || info.expectedSha256.trim().isEmpty()) {
            throw new IllegalStateException("no SHA-256 for the APK");
        }
        File dir = new File(context.getCacheDir(), OTA_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File part = new File(dir, APK_NAME + ".part");
        File apk = new File(dir, APK_NAME);
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        //noinspection ResultOfMethodCallIgnored
        apk.delete();

        HttpURLConnection connection = open(info.downloadUrl);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long done = 0L;
        try {
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    if (progress != null) progress.onProgress(done, total);
                }
            }
        } finally {
            connection.disconnect();
        }

        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(info.expectedSha256.trim())) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new SecurityException("SHA-256 mismatch");
        }
        if (!part.renameTo(apk)) {
            throw new IllegalStateException("cannot rename " + part);
        }
        Log.i(TAG, "downloaded " + info.latestVersion + " (" + done + " bytes, SHA-256 ok)");
        return apk;
    }

    /** GitHub answers with a redirect to its file host; followed by hand, as MG4Dash does. */
    private static HttpURLConnection open(String url) throws Exception {
        URL current = new URL(url);
        for (int hops = 0; ; hops++) {
            // HTTPS only, redirects included: an update must never come over plain HTTP.
            if (!"https".equalsIgnoreCase(current.getProtocol())) {
                throw new IllegalStateException("refusing non-HTTPS URL " + current.getProtocol());
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "DiAuto-MG4/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && hops < MAX_REDIRECTS) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IllegalStateException("HTTP " + code + " without Location");
                current = new URL(current, location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IllegalStateException("HTTP " + code);
            }
            return connection;
        }
    }

    static File stagedApk(Context context) {
        return new File(new File(context.getCacheDir(), OTA_DIR), APK_NAME);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
