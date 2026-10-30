package com.andrerinas.openheadunit.ota

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class OtaReleaseAssetsTest {

    private val release = JSONArray().also { array ->
        listOf("DiAuto-MG4-v1.9.8-phone.apk", "DiAuto-MG4-v1.9.8-car.apk", "SHA256SUMS").forEach {
            array.put(JSONObject().put("name", it).put("browser_download_url", "https://example.invalid/$it"))
        }
    }

    @Test
    fun carFlavorPicksCarApk() {
        assertEquals("DiAuto-MG4-v1.9.8-car.apk", OtaReleaseAssets.selectGitHubApkAsset(release, "car").name)
    }

    @Test
    fun phoneFlavorPicksPhoneApk() {
        assertEquals("DiAuto-MG4-v1.9.8-phone.apk", OtaReleaseAssets.selectGitHubApkAsset(release, "phone").name)
    }
}
