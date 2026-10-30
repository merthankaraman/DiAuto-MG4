package com.andrerinas.openheadunit.ota;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class OtaReleaseAssets {

    private OtaReleaseAssets() {
    }

    static ApkAsset selectGitHubApkAsset(JSONArray assets) {
        return selectGitHubApkAsset(assets, null);
    }

    /** Flavor ("car" or "phone") makes sure a head unit never downloads the phone APK and vice versa. */
    static ApkAsset selectGitHubApkAsset(JSONArray assets, String flavor) {
        if (assets == null || assets.length() == 0) return null;

        List<ApkAsset> candidates = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (!name.toLowerCase(Locale.US).endsWith(".apk") || downloadUrl.isEmpty()) continue;
            candidates.add(new ApkAsset(name, downloadUrl));
        }
        return chooseBestApkAsset(candidates, flavor);
    }

    static HashAsset selectGitHubHashAsset(JSONArray assets, String apkFileName) {
        if (assets == null || assets.length() == 0 || apkFileName == null || apkFileName.isEmpty()) return null;
        String expectedSidecarName = apkFileName + ".sha256";
        HashAsset fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            String downloadUrl = asset.optString("browser_download_url", "");
            if (downloadUrl.isEmpty()) continue;
            if (expectedSidecarName.equalsIgnoreCase(name)) {
                return new HashAsset(name, downloadUrl);
            }
            if ("SHA256SUMS".equalsIgnoreCase(name)) {
                fallback = new HashAsset(name, downloadUrl);
            }
        }
        return fallback;
    }

    static String parseExpectedSha256(String hashFileContent, String apkFileName) {
        if (hashFileContent == null) return null;
        String[] lines = hashFileContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 1 && isSha256(parts[0])) {
                return parts[0].toLowerCase(Locale.US);
            }
            if (parts.length >= 2 && isSha256(parts[0])) {
                String candidateName = parts[parts.length - 1].replace("*", "");
                if (apkFileName.equals(candidateName) || trimmed.endsWith(apkFileName)) {
                    return parts[0].toLowerCase(Locale.US);
                }
            }
        }
        return null;
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static ApkAsset chooseBestApkAsset(List<ApkAsset> candidates, String flavor) {
        if (candidates == null || candidates.isEmpty()) return null;
        ApkAsset best = candidates.get(0);
        int bestScore = scoreAsset(best.name, flavor);
        for (int i = 1; i < candidates.size(); i++) {
            ApkAsset candidate = candidates.get(i);
            int score = scoreAsset(candidate.name, flavor);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static int scoreAsset(String name, String flavor) {
        String lower = name.toLowerCase(Locale.US);
        int score = 0;
        if (flavor != null && !flavor.isEmpty()) {
            String wanted = "-" + flavor.toLowerCase(Locale.US) + ".apk";
            String other = "-" + ("car".equalsIgnoreCase(flavor) ? "phone" : "car") + ".apk";
            if (lower.endsWith(wanted)) score += 100;
            if (lower.endsWith(other)) score -= 100;
        }
        if (lower.endsWith(".apk")) score += 10;
        if (lower.contains("diauto-mg4") || lower.contains("diauto_mg4") || lower.contains("diauto")) score += 6;
        if (lower.contains("release")) score += 5;
        if (lower.contains("platform")) score += 4;
        if (lower.contains("debug")) score -= 8;
        return score;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    static final class ApkAsset {
        final String name;
        final String downloadUrl;

        ApkAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }

    static final class HashAsset {
        final String name;
        final String downloadUrl;

        HashAsset(String name, String downloadUrl) {
            this.name = name;
            this.downloadUrl = downloadUrl;
        }
    }
}
