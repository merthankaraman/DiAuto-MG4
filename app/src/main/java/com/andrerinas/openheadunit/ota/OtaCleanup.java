package com.andrerinas.openheadunit.ota;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Locale;

/** İndirilenler’deki eski DiAuto-MG4_*.apk (ve eski diauto_mg4_*) dosyalarını temizler. */
final class OtaCleanup {

    private static final String TAG = "DiAuto_OTA";
    private static final FilenameFilter APK_FILTER = (dir, name) -> {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return (lower.startsWith("diauto-mg4_") || lower.startsWith("diauto_mg4_"))
                && (lower.endsWith(".apk") || lower.endsWith(".apk.sha256"));
    };

    private OtaCleanup() {
    }

    /** Tüm OTA APK / hash dosyalarını sil (kurulum sonrası), the app cache included. */
    static void deleteAllOtaApks(Context context) {
        deleteOldApks(null);
        File[] staged = new File(context.getCacheDir(), OtaDownloader.OTA_DIR).listFiles();
        if (staged == null) return;
        for (File f : staged) {
            if (f.isFile() && f.delete()) Log.i(TAG, "OTA cache deleted: " + f.getName());
        }
    }

    /**
     * Eski OTA paketlerini sil.
     * @param keep silinmeyecek dosya (şu an indirilen / kurulan); null = hepsini sil
     */
    static void deleteOldApks(File keep) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null || !dir.isDirectory()) {
            Log.w(TAG, "cleanup: Downloads yok");
            return;
        }
        File[] files = dir.listFiles(APK_FILTER);
        if (files == null || files.length == 0) return;

        String keepPath = null;
        try {
            if (keep != null) keepPath = keep.getCanonicalPath();
        } catch (Exception ignored) {
            if (keep != null) keepPath = keep.getAbsolutePath();
        }

        int deleted = 0;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                if (keepPath != null && keepPath.equals(f.getCanonicalPath())) continue;
            } catch (Exception e) {
                if (keepPath != null && keepPath.equals(f.getAbsolutePath())) continue;
            }
            if (f.delete()) {
                deleted++;
                Log.i(TAG, "eski APK silindi: " + f.getName());
            } else {
                Log.w(TAG, "silinemedi: " + f.getName());
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "OTA cleanup: " + deleted + " dosya silindi");
        }
    }
}
