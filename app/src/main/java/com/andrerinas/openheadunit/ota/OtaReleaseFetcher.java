package com.andrerinas.openheadunit.ota;

import android.content.Context;

import com.andrerinas.openheadunit.BuildConfig;
import com.andrerinas.openheadunit.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OtaReleaseFetcher {

    /*
     * TODO: Rendere configurabile l'URL del repository GitHub (nelle impostazioni di OpenHeadUnit)
     */
    private static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/merthankaraman/DiAuto-MG4/releases?per_page=20";

    private OtaReleaseFetcher() {
    }

    static OtaUpdateManager.UpdateInfo fetchLatestRelease(Context context, boolean allowBetaUpdates) {
        try {
            JSONArray releases = new JSONArray(readUrl(GITHUB_RELEASES_URL, "application/vnd.github+json"));
            JSONObject release = selectGitHubRelease(releases, allowBetaUpdates);
            if (release == null) {
                return failure(context.getString(R.string.ota_error_no_release), allowBetaUpdates);
            }
            return buildUpdateInfoFromGitHubRelease(context, release, allowBetaUpdates);
        } catch (Exception e) {
            return failure(e.getClass().getSimpleName(), allowBetaUpdates);
        }
    }

    private static OtaUpdateManager.UpdateInfo buildUpdateInfoFromGitHubRelease(
            Context context,
            JSONObject release,
            boolean allowBetaUpdates
    ) throws Exception {
        String latestVersion = normalizeVersion(
                OtaReleaseAssets.firstNonEmpty(
                        release.optString("tag_name", null),
                        release.optString("name", null),
                        BuildConfig.VERSION_NAME
                )
        );
        String releaseName = OtaReleaseAssets.firstNonEmpty(release.optString("name", null), latestVersion);
        String releaseNotes = OtaReleaseAssets.firstNonEmpty(release.optString("body", null), "");
        boolean prerelease = release.optBoolean("prerelease", false);

        OtaReleaseAssets.ApkAsset apkAsset = OtaReleaseAssets.selectGitHubApkAsset(release.optJSONArray("assets"));
        if (apkAsset == null) {
            return new OtaUpdateManager.UpdateInfo(
                    false, false, BuildConfig.VERSION_NAME, latestVersion, releaseName, releaseNotes,
                    null, null, null, context.getString(R.string.ota_error_no_apk), prerelease
            );
        }

        boolean updateAvailable = compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0;
        String expectedSha256 = null;
        if (updateAvailable) {
            OtaReleaseAssets.HashAsset hashAsset =
                    OtaReleaseAssets.selectGitHubHashAsset(release.optJSONArray("assets"), apkAsset.name);
            if (hashAsset == null) {
                return new OtaUpdateManager.UpdateInfo(
                        true, true, BuildConfig.VERSION_NAME, latestVersion, releaseName, releaseNotes,
                        apkAsset.downloadUrl, apkAsset.name, null,
                        context.getString(R.string.ota_error_no_hash), prerelease
                );
            }
            String hashFileContent = readUrl(hashAsset.downloadUrl, "text/plain, application/octet-stream");
            expectedSha256 = OtaReleaseAssets.parseExpectedSha256(hashFileContent, apkAsset.name);
            if (expectedSha256 == null || expectedSha256.isEmpty()) {
                return new OtaUpdateManager.UpdateInfo(
                        true, true, BuildConfig.VERSION_NAME, latestVersion, releaseName, releaseNotes,
                        apkAsset.downloadUrl, apkAsset.name, null,
                        context.getString(R.string.ota_error_invalid_hash), prerelease
                );
            }
        }

        return new OtaUpdateManager.UpdateInfo(
                true,
                updateAvailable,
                BuildConfig.VERSION_NAME,
                latestVersion,
                releaseName,
                releaseNotes,
                apkAsset.downloadUrl,
                apkAsset.name,
                expectedSha256,
                updateAvailable
                        ? context.getString(R.string.ota_status_update_available, latestVersion)
                        : context.getString(R.string.ota_status_up_to_date),
                prerelease
        );
    }

    private static OtaUpdateManager.UpdateInfo failure(String detail, boolean allowBetaUpdates) {
        return new OtaUpdateManager.UpdateInfo(
                false, false, BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME,
                null, null, null, null, null, detail, false
        );
    }

    private static String readUrl(String url, String accept) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "DiAuto-MG4/" + BuildConfig.VERSION_NAME);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return readFully(connection.getInputStream());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static JSONObject selectGitHubRelease(JSONArray releases, boolean allowBetaUpdates) {
        if (releases == null || releases.length() == 0) return null;

        JSONObject best = null;
        String bestVersion = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject candidate = releases.optJSONObject(i);
            if (candidate == null || candidate.optBoolean("draft", false)) continue;
            boolean prerelease = candidate.optBoolean("prerelease", false);
            if (prerelease && !allowBetaUpdates) continue;

            String candidateVersion = normalizeVersion(OtaReleaseAssets.firstNonEmpty(
                    candidate.optString("tag_name", null),
                    candidate.optString("name", null),
                    ""
            ));
            if (candidateVersion.isEmpty()) continue;

            if (best == null) {
                best = candidate;
                bestVersion = candidateVersion;
                continue;
            }

            int compare = compareVersions(candidateVersion, bestVersion);
            if (compare > 0 || (compare == 0 && !prerelease && best.optBoolean("prerelease", false))) {
                best = candidate;
                bestVersion = candidateVersion;
            }
        }
        return best;
    }

    private static String readFully(InputStream inputStream) throws Exception {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        while (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        while (!trimmed.isEmpty() && !Character.isDigit(trimmed.charAt(0))) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    static int compareVersions(String leftRaw, String rightRaw) {
        String left = normalizeVersion(leftRaw);
        String right = normalizeVersion(rightRaw);
        String[] leftParts = left.split("[^0-9]+");
        String[] rightParts = right.split("[^0-9]+");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int l = parsePart(leftParts, i);
            int r = parsePart(rightParts, i);
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        String part = parts[index];
        if (part == null || part.isEmpty()) return 0;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
